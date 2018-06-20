package edu.wpi.first.shuffleboard.app.components.skin;

import edu.wpi.first.shuffleboard.app.components.Timeline;

import com.sun.javafx.util.Utils;

import de.jensd.fx.glyphs.fontawesome.FontAwesomeIcon;
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIconView;

import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.monadic.MonadicBinding;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.beans.binding.DoubleBinding;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.ListChangeListener;
import javafx.css.PseudoClass;
import javafx.event.EventHandler;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Skin;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.ClosePath;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.util.Builder;
import javafx.util.Duration;

@SuppressWarnings("JavadocMethod")
public final class TimelineSkin implements Skin<Timeline> {

  private final ListChangeListener<Timeline.Marker> markerListChangeListener;

  private static final PseudoClass current = PseudoClass.getPseudoClass("current");
  private static final PseudoClass importanceLowest = PseudoClass.getPseudoClass("importance-lowest");
  private static final PseudoClass importanceLow = PseudoClass.getPseudoClass("importance-low");
  private static final PseudoClass importanceNormal = PseudoClass.getPseudoClass("importance-normal");
  private static final PseudoClass importanceHigh = PseudoClass.getPseudoClass("importance-high");
  private static final PseudoClass importanceHighest = PseudoClass.getPseudoClass("importance-highest");

  private static final Map<Timeline.Importance, PseudoClass> importanceClasses =
      new EnumMap<>(Timeline.Importance.class);

  static {
    importanceClasses.put(Timeline.Importance.LOWEST, importanceLowest);
    importanceClasses.put(Timeline.Importance.LOW, importanceLow);
    importanceClasses.put(Timeline.Importance.NORMAL, importanceNormal);
    importanceClasses.put(Timeline.Importance.HIGH, importanceHigh);
    importanceClasses.put(Timeline.Importance.HIGHEST, importanceHighest);
  }

  private Timeline control;
  private Pane root;
  private final Pane track;
  private final Map<Timeline.Marker, Node> markerMap = new HashMap<>();
  private final Map<Double, Timeline.Marker> markerPositions = new LinkedHashMap<>();
  private final Label detail = new Label();
  private Timeline.Marker lastMarker = null;
  private final ObjectProperty<Timeline.Marker> displayedMarker = new SimpleObjectProperty<>();
  private final MonadicBinding<Double> currentMarkerX = EasyBind.monadic(displayedMarker) // NOPMD could be local var
      .flatMap(m -> markerMap.get(m).layoutXProperty())
      .map(Number::doubleValue)
      .orElse(0.0);

  private final javafx.animation.Timeline animation = new javafx.animation.Timeline();
  private boolean tempView = false;
  private boolean hidingDetailLabel = false;

  public TimelineSkin(Timeline control) {
    this.control = control;
    root = new VBox(2);
    track = new Pane();
    track.minHeightProperty().bind(root.minHeightProperty());
    track.prefHeightProperty().bind(root.prefHeightProperty());
    track.maxHeightProperty().bind(root.maxHeightProperty());
    root.setMinHeight(20);
    root.setMaxHeight(20);
    track.getStyleClass().add("track");
    root.getChildren().add(track);
    DoubleBinding len = control.endProperty().subtract(control.startProperty());
    markerListChangeListener = c -> {
      while (c.next()) {
        if (c.wasAdded()) {
          for (Timeline.Marker marker : c.getAddedSubList()) {
            Node markerHandle = createMarkerHandle(marker);
            markerHandle.layoutXProperty().bind(marker.positionProperty().divide(len).multiply(track.widthProperty()));
            markerMap.put(marker, markerHandle);
            track.getChildren().add(0, markerHandle);
          }
        } else if (c.wasRemoved()) {
          for (Timeline.Marker marker : c.getRemoved()) {
            Node removedMarkerHandle = markerMap.remove(marker);
            track.getChildren().remove(removedMarkerHandle);
          }
        }
      }
      c.getList().forEach(marker -> markerPositions.put(marker.getPosition(), marker));
    };
    for (Timeline.Marker marker : control.getMarkers()) {
      Node markerHandle = createMarkerHandle(marker);
      markerHandle.layoutXProperty().bind(marker.positionProperty().divide(len).multiply(track.widthProperty()));
      markerMap.put(marker, markerHandle);
      track.getChildren().add(0, markerHandle);
    }
    control.getMarkers().forEach(marker -> markerPositions.put(marker.getPosition(), marker));
    control.getMarkers().addListener(markerListChangeListener);
    Path progressHandle = createProgressHandle();
    progressHandle.layoutXProperty().bind(control.progressProperty().divide(len).multiply(track.widthProperty()));

    control.playingProperty().addListener((__, was, is) -> {
      if (is) {
        startAnimation();
      } else {
        animation.stop();
      }
    });

    if (control.isPlaying()) {
      startAnimation();
    }
    displayedMarker.addListener((__, old, marker) -> {
      if (marker != null) {
        importanceClasses.forEach((p, c) -> {
          detail.pseudoClassStateChanged(c, p == marker.getImportance());
        });
      }
    });
    control.progressProperty().addListener((__, old, progress) -> {
      final double o = old.doubleValue();
      final double p = progress.doubleValue();
      markerPositions.entrySet().stream()
          .filter(e -> e.getKey() == p || (e.getKey() >= o && e.getKey() <= p))
          .map(e -> e.getValue())
          .max(Comparator.comparingDouble(Timeline.Marker::getPosition))
          .ifPresent(marker -> {
            if (lastMarker != null) {
              markerMap.get(lastMarker).pseudoClassStateChanged(current, false);
            }
            markerMap.get(marker).pseudoClassStateChanged(current, true);
            lastMarker = marker;
          });
      if (tempView) {
        return;
      }
      tempView = false;
      double adjustedTimeDelta = Math.abs(lastMarker.getPosition() - p) / control.getPlaybackSpeed();
      if (progressToTime(adjustedTimeDelta).compareTo(control.getDetailTimeout()) >= 0) {
        hideDetail();
      } else {
        displayedMarker.set(lastMarker);
        detail.setText(makeText(lastMarker));
      }
    });
    detail.getStyleClass().add("detail-label");
    detail.maxWidthProperty().bind(track.widthProperty());
    detail.setLayoutY(1);
    detail.prefHeightProperty().bind(track.heightProperty().subtract(2));
    detail.maxHeightProperty().bind(detail.prefHeightProperty());
    detail.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS);
    detail.textProperty().addListener((__, old, text) -> {
      if (!detail.isVisible()) {
        FadeTransition fadeTransition = new FadeTransition(Duration.millis(400), detail);
        detail.setVisible(true);
        fadeTransition.setToValue(1);
        fadeTransition.setFromValue(0);
        fadeTransition.playFromStart();
      }
    });
    detail.layoutXProperty().bind(
        EasyBind.combine(
            currentMarkerX, detail.widthProperty(), track.widthProperty(),
            TimelineSkin::computeDetailLabelPosition
        ));
    detail.setOnMouseExited(__ -> {
      if (tempView) {
        hideDetail();
        tempView = false;
      }
    });
    detail.setVisible(false);
    track.getChildren().add(detail);
    track.getChildren().add(progressHandle);

    Label time = new Label();
    time.textProperty().bind(EasyBind.monadic(control.progressProperty()).map(p -> {
      return toString(progressToTime(p.doubleValue()).toMillis());
    }));
    HBox controls = createControls();
    BorderPane foot = new BorderPane();
    foot.setLeft(time);
    foot.setRight(controls);
    root.getChildren().add(foot);
    control.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
      switch (e.getCode()) {
        case SPACE:
          control.setPlaying(!control.isPlaying());
          break;
        case KP_LEFT: // fallthrough
        case LEFT:
          moveToPreviousMarker();
          break;
        case KP_RIGHT: // fallthrough
        case RIGHT:
          moveToNextMarker();
          break;
        default:
          break;
      }
    });
  }

  private static double computeDetailLabelPosition(double markerX, Number labelWidth, Number trackWidth) {
    return Utils.clamp(
        0,
        markerX - labelWidth.doubleValue() / 2,
        trackWidth.doubleValue() - labelWidth.doubleValue()
    );
  }

  private void hideDetail() {
    if (detail.isVisible() && !hidingDetailLabel) {
      hidingDetailLabel = true;
      FadeTransition fadeTransition = new FadeTransition(Duration.millis(400), detail);
      fadeTransition.setToValue(0);
      fadeTransition.setFromValue(1);
      fadeTransition.setOnFinished(__ -> {
        detail.setVisible(false);
        markerMap.get(lastMarker).pseudoClassStateChanged(current, false);
        hidingDetailLabel = false;
      });
      fadeTransition.playFromStart();
    }
  }

  private static boolean isNullOrEmpty(String text) {
    return text == null || text.isEmpty() || text.chars().allMatch(Character::isWhitespace);
  }

  private HBox createControls() {
    HBox controls = new HBox(4);
    controls.getStyleClass().add("controls");
    FontAwesomeIconView prev = new FontAwesomeIconView(FontAwesomeIcon.BACKWARD);
    FontAwesomeIconView playPause = new FontAwesomeIconView(FontAwesomeIcon.PLAY);
    FontAwesomeIconView next = new FontAwesomeIconView(FontAwesomeIcon.FORWARD);
    FontAwesomeIconView loop = new FontAwesomeIconView(FontAwesomeIcon.REPEAT);

    Tooltip.install(prev, new Tooltip("Previous marker"));
    Tooltip ppt = new Tooltip();
    ppt.textProperty().bind(EasyBind.monadic(playPause.glyphNameProperty())
        .map(n -> n.toLowerCase(Locale.US)));
    Tooltip.install(playPause, ppt);
    Tooltip.install(next, new Tooltip("Next marker"));
    Tooltip.install(loop, new Tooltip("Repeat"));

    prev.setOnMouseClicked(__ -> moveToPreviousMarker());
    playPause.setOnMouseClicked(__ -> control.setPlaying(!control.isPlaying()));
    next.setOnMouseClicked(__ -> moveToNextMarker());
    loop.setOnMouseClicked(__ -> {
      boolean doLoop = !control.isLoopPlayback();
      control.setLoopPlayback(doLoop);
      if (doLoop && control.getProgress() == control.getEnd() && control.isPlaying()) {
        control.setProgress(control.getStart());
      }
      animation.setCycleCount(doLoop ? -1 : 1);
      if (control.isPlaying()) {
        animation.stop();
        animation.playFrom(progressToTime(control.getProgress()));
      }
      loop.pseudoClassStateChanged(PseudoClass.getPseudoClass("selected"), doLoop);
    });

    playPause.glyphNameProperty().bind(EasyBind.monadic(control.playingProperty())
        .map(playing -> {
          if (playing) {
            return "PAUSE";
          } else {
            return "PLAY";
          }
        }));

    controls.getChildren().addAll(
        prev,
        playPause,
        next,
        loop
    );
    return controls;
  }

  private void startAnimation() {
    animation.getKeyFrames().setAll(
        new KeyFrame(Duration.ZERO, new KeyValue(control.progressProperty(), control.getStart())),
        new KeyFrame(control.getLength(), new KeyValue(control.progressProperty(), control.getEnd()))
    );
    animation.setRate(control.getPlaybackSpeed());
    animation.playFrom(progressToTime(control.getProgress()));
  }

  private void moveToPreviousMarker() {
    Timeline.Marker closest = null;
    for (Timeline.Marker marker : markerMap.keySet()) {
      double diff = marker.getPosition() - control.getProgress();
      if (diff >= 0) {
        // Either the current control or one ahead of the current position; either way, it's not a marker we want
        continue;
      }
      double lastDiff = closest == null ? 1 : closest.getPosition() - control.getProgress();
      if (closest == null || diff > lastDiff) {
        closest = marker;
      }
    }
    if (closest != null) {
      control.setPlaying(false);
      setMarker(closest);
    }
  }

  private void moveToNextMarker() {
    Timeline.Marker closest = null;
    for (Timeline.Marker marker : markerMap.keySet()) {
      double diff = marker.getPosition() - control.getProgress();
      if (diff <= 0) {
        // Either the current control or one behind the current position; either way, it's not a marker we want
        continue;
      }
      double lastDiff = closest == null ? -1 : closest.getPosition() - control.getProgress();
      if (closest == null || diff < lastDiff) {
        closest = marker;
      }
    }
    if (closest != null) {
      control.setPlaying(false);
      setMarker(closest);
    }
  }

  private void setMarker(Timeline.Marker marker) {
    control.setProgress(marker.getPosition());
    detail.setText(makeText(marker));
    detail.setVisible(true);
    detail.setOpacity(1);
    if (lastMarker != null) {
      markerMap.get(lastMarker).pseudoClassStateChanged(current, false);
    }
    lastMarker = marker;
    markerMap.get(marker).pseudoClassStateChanged(current, true);
  }

  private String makeText(Timeline.Marker marker) {
    Duration time = progressToTime(marker.getPosition());
    String timeString = toString(time.toMillis());
    if (isNullOrEmpty(marker.getDescription())) {
      return timeString + " - " + marker.getName();
    } else {
      return timeString + " - " + marker.getName() + ": " + marker.getDescription();
    }
  }

  /**
   * Converts milliseconds to a formatted string in the format {@code HH:MM:SS.mmm}.
   *
   * @param millis the number of milliseconds
   */
  @SuppressWarnings("UnnecessaryParentheses")
  private static String toString(double millis) {
    int hh = (int) (millis / (3_600_000));
    int mm = (int) ((millis / (60_000)) % 60);
    int ss = (int) (millis / 1000) % 60;
    int mmm = (int) (millis % 1000);
    return String.format("%02d:%02d:%02d.%03d", hh, mm, ss, mmm);
  }

  private Node createMarkerHandle(Timeline.Marker marker) {
    Timeline control = getSkinnable();
    // Diamond shape
    Path handle = new Path(
        new MoveTo(-4, 0),
        new LineTo(0, -4),
        new LineTo(4, 0),
        new LineTo(0, 4),
        new ClosePath()
    );
    handle.getStyleClass().add("marker-handle");
    handle.setOnMousePressed(__ -> {
      tempView = false;
      control.setPlaying(false);
      control.setProgress(marker.getPosition());
      detail.setText(makeText(marker));
    });
    handle.setOnMouseEntered(__ -> {
      tempView = true;
      displayedMarker.set(marker);
      detail.setText(makeText(marker));
      detail.setOpacity(1);
      detail.setVisible(true);
      importanceClasses.forEach((p, c) -> {
        detail.pseudoClassStateChanged(c, p == marker.getImportance());
      });
    });
    handle.setOnMouseExited(e -> {
      if (tempView && !detail.contains(detail.screenToLocal(e.getScreenX(), e.getScreenY()))) {
        hideDetail();
        tempView = false;
      }
    });
    marker.importanceProperty().addListener(__ -> {
      importanceClasses.forEach((p, c) -> {
        handle.pseudoClassStateChanged(c, p == marker.getImportance());
      });
    });
    importanceClasses.forEach((p, c) -> {
      handle.pseudoClassStateChanged(c, p == marker.getImportance());
    });
    return handle;
  }

  private Path createProgressHandle() {
    Path handle = new Path(
        new MoveTo(-5, -7),
        new LineTo(5, -7),
        new LineTo(5, 2),
        new LineTo(0, 7),
        new LineTo(-5, 2),
        new ClosePath()
    );
    handle.getStyleClass().add("progress-handle");
    EventHandler<MouseEvent> resumeOnDoubleClick = e -> {
      if (e.getClickCount() == 2) {
        animation.playFrom(progressToTime(control.getProgress()));
      }
    };
    makeDraggable(handle);
    handle.addEventHandler(MouseEvent.MOUSE_CLICKED, resumeOnDoubleClick);
    return handle;
  }

  private void makeDraggable(Node handle) {
    handle.setOnMousePressed(__ -> control.setPlaying(false));
    handle.setOnMouseDragged(e -> {
      Point2D cur = handle.localToParent(e.getX(), e.getY());
      double dragPos = cur.getX();
      double progress = Utils.clamp(
          control.getStart(),
          (dragPos / track.getWidth()) * (control.getEnd() - control.getStart()),
          control.getEnd()
      );
      control.setProgress(progress);
    });
  }

  private Duration progressToTime(double progress) {
    return control.getLength().multiply(progress / (control.getEnd() - control.getStart()));
  }

  @Override
  public Timeline getSkinnable() {
    return control;
  }

  @Override
  public Node getNode() {
    return root;
  }

  @Override
  public void dispose() {
    control.getMarkers().removeListener(markerListChangeListener);
    markerMap.clear();
    markerPositions.clear();
    control = null;
    root = null;
  }

}