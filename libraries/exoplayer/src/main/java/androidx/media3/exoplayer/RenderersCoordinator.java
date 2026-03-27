package androidx.media3.exoplayer;

import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Util.castNonNull;

import android.os.Handler;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Timeline;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.NullableType;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.metadata.MetadataOutput;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.trackselection.TrackSelector;
import androidx.media3.exoplayer.trackselection.TrackSelectorResult;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import com.google.common.base.Supplier;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/* package */ class RenderersCoordinator {

  @FunctionalInterface
      /*package*/ interface WithLock {
    void consume() throws ExoPlaybackException, IOException, RuntimeException;
  }

  /*package*/ interface OnRenderersReevaluated {
    void onRenderersReevaluated();
  }

  private final PlayerId playerId;
  private final Handler eventHandler;
  private final TrackSelector trackSelector;
  private final Clock clock;

  private final Object lock = new Object();
  /*package*/ RendererHolder[] rendererHolders;
  /*package*/ RendererCapabilities[] rendererCapabilities;
  /*package*/ boolean[] rendererReportedReady;
  /*package*/  Renderer[] renderers;
  /*package*/ @NullableType Renderer[] secondaryRenderers;
  private final Supplier<RenderersFactory> renderersFactorySupplier;
  private final VideoRendererEventListener videoRendererEventListener;
  private final AudioRendererEventListener audioRendererEventListener;
  private final TextOutput textRendererOutput;
  private final MetadataOutput metadataRendererOutput;
  boolean hasSecondaryRenderers;

  private final List<OnRenderersReevaluated> listeners;

  /**
   * This empty track selector result can only be used for {@link PlaybackInfo#trackSelectorResult}
   * when the player does not have any track selection made (such as when player is reset, or when
   * player seeks to an unprepared period). It will not be used as result of any {@link
   * TrackSelector#selectTracks(RendererCapabilities[], TrackGroupArray, MediaPeriodId, Timeline)}
   * operation.
   */
  /* package */ TrackSelectorResult emptyTrackSelectorResult;

  public RenderersCoordinator(
      PlayerId playerId,
      Handler eventHandler,
      TrackSelector trackSelector,
      Clock clock,
      Supplier<RenderersFactory> renderersFactorySupplier,
      VideoRendererEventListener videoRendererEventListener,
      AudioRendererEventListener audioRendererEventListener,
      TextOutput textRendererOutput,
      MetadataOutput metadataRendererOutput) {
    this.playerId = playerId;
    this.eventHandler = eventHandler;
    this.trackSelector = trackSelector;
    this.clock = clock;

    RenderersFactory renderersFactory = renderersFactorySupplier.get();

    renderers =
        renderersFactory
            .createRenderers(
                eventHandler,
                videoRendererEventListener,
                audioRendererEventListener,
                textRendererOutput,
                metadataRendererOutput);
    checkState(renderers.length > 0);
    secondaryRenderers = new Renderer[renderers.length];
    for (int i = 0; i < secondaryRenderers.length; i++) {
      // TODO(b/377671489): Fix DefaultAnalyticsCollector logic to still work with pre-warming.
      secondaryRenderers[i] =
          renderersFactory.createSecondaryRenderer(
              renderers[i],
              eventHandler,
              videoRendererEventListener,
              audioRendererEventListener,
              textRendererOutput,
              metadataRendererOutput);
    }

    rendererCapabilities = new RendererCapabilities[renderers.length];
    rendererReportedReady = new boolean[renderers.length];
    @Nullable
    RendererCapabilities.Listener rendererCapabilitiesListener =
        trackSelector.getRendererCapabilitiesListener();

    boolean hasSecondaryRenderers = false;
    this.rendererHolders = new RendererHolder[renderers.length];
    for (int i = 0; i < renderers.length; i++) {
      renderers[i].init(/* index= */ i, playerId, clock);
      rendererCapabilities[i] = renderers[i].getCapabilities();
      if (rendererCapabilitiesListener != null) {
        rendererCapabilities[i].setListener(rendererCapabilitiesListener);
      }
      if (secondaryRenderers[i] != null) {
        castNonNull(secondaryRenderers[i]).init(/* index= */ i, playerId, clock);
        hasSecondaryRenderers = true;
      }
      this.rendererHolders[i] = new RendererHolder(
          renderers[i], secondaryRenderers[i], /* index= */ i);
    }
    this.hasSecondaryRenderers = hasSecondaryRenderers;

    this.renderersFactorySupplier = renderersFactorySupplier;
    this.videoRendererEventListener = videoRendererEventListener;
    this.audioRendererEventListener = audioRendererEventListener;
    this.textRendererOutput = textRendererOutput;
    this.metadataRendererOutput = metadataRendererOutput;
    this.emptyTrackSelectorResult = newEmptyTrackSelectorResult();
    this.listeners = new ArrayList<>();
  }

  public void addListener(OnRenderersReevaluated listener) {
    this.listeners.add(listener);
  }

  public void removeListener(OnRenderersReevaluated listener) {
    this.listeners.remove(listener);
  }

  public void reevaluate(TrackGroupArray trackGroups) {
    // Obtain the new list of renderers
    Renderer[] newRenderers = renderersFactorySupplier.get()
        .reevaluateRenderers(
            renderers,
            trackGroups,
            eventHandler,
            videoRendererEventListener,
            audioRendererEventListener,
            textRendererOutput,
            metadataRendererOutput
        );
    Renderer[] newSecondaryRenderers = new Renderer[newRenderers.length];
    RendererHolder[] newRendererHolders = new RendererHolder[newRenderers.length];

    // WARNING!! Previous renderers cannot be destroyed and must be kept in the same order.
    // Otherwise the new allocation cannot be accepted.
    int curlen = renderers.length;
    if (curlen > newRenderers.length) return;
    for (int i = 0; i < curlen; i++) {
      if (renderers[i] != newRenderers[i]) return;
    }

    renderers = newRenderers;
    rendererReportedReady = newRendererReportedReady();
    rendererCapabilities = newRendererCapabilitiesList();

    for (int i = 0; i < curlen; i++) {
      newSecondaryRenderers[i] = secondaryRenderers[i];
      newRendererHolders[i] = rendererHolders[i];
    }

    // Init new allocated renderers
    for (int i = curlen; i < renderers.length; i++) {
      renderers[i].init(/* index= */ i, playerId, clock);
      rendererCapabilities[i] = renderers[i].getCapabilities();

      @Nullable
      RendererCapabilities.Listener listener = trackSelector.getRendererCapabilitiesListener();
      if (listener != null) {
        rendererCapabilities[i].setListener(listener);
      }

      initSecondaryRendererOnReevaluate(i);

      newRendererHolders[i] = new RendererHolder(
          renderers[i], newSecondaryRenderers[i], /* index= */ i);
    }

    secondaryRenderers = newSecondaryRenderers;
    rendererHolders = newRendererHolders;

    emptyTrackSelectorResult = newEmptyTrackSelectorResult();

    // Update period holders
    for (OnRenderersReevaluated listener : listeners) {
      listener.onRenderersReevaluated();
    }
  }

  public int getRendererCount() {
    synchronized (lock) {
      return renderers.length;
    }
  }

  public @C.TrackType int getRendererType(int index) {
    synchronized (lock) {
      return renderers[index].getTrackType();
    }
  }

  public Renderer getRenderer(int index) {
    synchronized (lock) {
      return renderers[index];
    }
  }

  @Nullable
  public Renderer getSecondaryRenderer(int index) {
    synchronized (lock) {
      return secondaryRenderers[index];
    }
  }

  public TrackSelectorResult getEmptyTrackSelectorResult() {
    synchronized (lock) {
      return emptyTrackSelectorResult;
    }
  }

  /*package*/ void withLock(WithLock action)
      throws ExoPlaybackException, IOException, RuntimeException {
    synchronized (lock) {
      action.consume();
    }
  }

  private RendererCapabilities[] newRendererCapabilitiesList() {
    RendererCapabilities.Listener rendererCapabilitiesListener =
        trackSelector.getRendererCapabilitiesListener();
    RendererCapabilities[] rendererCapabilities = new RendererCapabilities[renderers.length];
    for (int i = 0; i < renderers.length; i++) {
      renderers[i].init(/* index= */ i, playerId, clock);
      rendererCapabilities[i] = renderers[i].getCapabilities();
      if (rendererCapabilitiesListener != null) {
        rendererCapabilities[i].setListener(rendererCapabilitiesListener);
      }
    }
    return rendererCapabilities;
  }

  private boolean[] newRendererReportedReady() {
    boolean[] newRendererReportedReady = new boolean[renderers.length];
    System.arraycopy(
        rendererReportedReady, 0, newRendererReportedReady, 0, rendererReportedReady.length);
    return newRendererReportedReady;
  }

  private TrackSelectorResult newEmptyTrackSelectorResult() {
    return new TrackSelectorResult(
        new RendererConfiguration[renderers.length],
        new ExoTrackSelection[renderers.length],
        Tracks.EMPTY,
        /* info= */ null);
  }

  private void initSecondaryRendererOnReevaluate(int i) {
    // TODO(b/377671489): Fix DefaultAnalyticsCollector logic to still work with pre-warming.
    secondaryRenderers[i] =
        renderersFactorySupplier.get().createSecondaryRenderer(
            renderers[i],
            eventHandler,
            videoRendererEventListener,
            audioRendererEventListener,
            textRendererOutput,
            metadataRendererOutput);

    if (secondaryRenderers[i] != null) {
      castNonNull(secondaryRenderers[i]).init(/* index= */ i + renderers.length, playerId, clock);
      this.hasSecondaryRenderers = true;
    }
  }
}