package app.audio.player;

import app.main.Aphrodite;
import app.main.TileManager;
import app.audio.AudioData;
import app.audio.PlayerComponents;
import app.audio.indexer.AudioDataIndexer;
import app.colors.dynamic.DynamicColors;
import app.components.PlaybackBar;
import app.components.audio.AudioInfoViewer;
import app.components.buttons.control.LikeButton;
import app.components.buttons.control.ShuffleButton;
import app.components.buttons.control.enums.RepeatMode;
import app.components.buttons.playback.PlayButton;
import app.components.buttons.playback.VolumeButton;
import app.components.containers.FullscreenPanel;
import app.dialogs.DialogFactory;
import app.local.notification.NotificationManager;
import app.settings.Shortcuts;
import app.settings.StartupSettings;
import material.animation.MaterialFixedTimer;
import material.theme.ThemeColors;
import material.theme.ThemeManager;
import material.tools.ColorUtils;
import material.tools.MaterialGraphics;
import material.utils.Log;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.Arrays;

public class AphroditeAudioController {
    private static AphroditeAudioController instance;
    //    private MediaPlayer m_player;
    private static final short SEEK_SECONDS = 5;
    private static final double VOLUME_CHANGE = 0.05d;
    private AudioPlayer _AudioPlayer;
    private AudioData currentAudioData;
    private PlayerComponents playerComponents;
    private boolean isLoaded = false;
    private boolean isPaused = true;
    private final RepeatMode repeatMode = RepeatMode.REPEAT_ALL;
    private double VOLUME = -1;
    private Thread UiUpdateTask;
    private Duration currentTime;
    private Duration durationToSeek = Duration.ofSeconds(0);
    private boolean isSeeking = false;
    private boolean wasPausedBeforeSeeking = false;
    private boolean isVisualizerSamplingEnabled;

    private AphroditeAudioController() {
        super();
    }

    public void handleKeyEvent(KeyEvent e) {
        try {
            if (durationToSeek.isZero()) {
                wasPausedBeforeSeeking = isPaused();
            }
            switch (e.getID()) {
                case KeyEvent.KEY_RELEASED -> {
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_PAUSE, Shortcuts.AudioPlayerController.PLAY_PAUSE_TOGGLE -> {
                            if (isPaused())
                                play();
                            else
                                pause();
                        }
                        case Shortcuts.AudioPlayerController.SEEK_BACKWARDS, Shortcuts.AudioPlayerController.SEEK_FORWARDS -> {
                            if (currentTime != null) {
                                if (playerComponents != null) {
                                    PlaybackBar playbackBar = playerComponents.getPlaybackBar();
                                    playbackBar.triggerSeekEvent(currentTime.plus(durationToSeek).toNanos());
                                    if (!wasPausedBeforeSeeking)
                                        play();
                                    isSeeking = false;
                                    durationToSeek = Duration.ofSeconds(0);
                                }
                            }
                        }
                    }
                }
                case KeyEvent.KEY_PRESSED -> {
                    switch (e.getKeyCode()) {
                        case Shortcuts.AudioPlayerController.SEEK_BACKWARDS -> {
                            if (currentTime != null) {
                                if (!isPaused())
                                    pause();
                                durationToSeek = durationToSeek.minusSeconds(SEEK_SECONDS);
                                playerComponents.getPlaybackBar().setCurrentTime(currentTime.plus(durationToSeek).toNanos());
                                isSeeking = true;
                            }
                        }
                        case Shortcuts.AudioPlayerController.SEEK_FORWARDS -> {
                            if (currentTime != null) {
                                if (!isPaused())
                                    pause();
                                durationToSeek = durationToSeek.plusSeconds(SEEK_SECONDS);
                                playerComponents.getPlaybackBar().setCurrentTime(currentTime.plus(durationToSeek).toNanos());
                                isSeeking = true;
                            }
                        }
                        case Shortcuts.AudioPlayerController.VOLUME_UP -> setVolume(VOLUME + VOLUME_CHANGE);
                        case Shortcuts.AudioPlayerController.VOLUME_DOWN -> setVolume(VOLUME - VOLUME_CHANGE);
                    }
                }
            }
        } catch (Exception er) {
            handleError(er);
        }
    }

    public void init() {
        try {

            if (_AudioPlayer != null) {
                _AudioPlayer.dispose();
                _AudioPlayer = null;
            }
            _AudioPlayer = new AudioPlayer();
            _AudioPlayer.addVisualizerDataListener(Spectrum.getInstance());
            _AudioPlayer.addMediaEndedListener(this::mediaEnded);
            _AudioPlayer.setThreshold(StartupSettings.SPECTRUM_THRESHOLD);
            _AudioPlayer.setSpectrumBands(StartupSettings.SPECTRUM_BANDS_NUM);
            _AudioPlayer.addExceptionListener(this::handleError);
            _AudioPlayer.enableVisualizerSampling(isVisualizerSamplingEnabled);
            VOLUME = _AudioPlayer.getVolume();
        } catch (Exception e) {
            handleError(e);
        }
    }


    public static AphroditeAudioController getInstance() {
        if (instance == null)
            instance = new AphroditeAudioController();
        return instance;
    }

    public synchronized void load(@Nullable AudioData audioData) {
        try {
            if (_AudioPlayer == null) {
                Log.error("call init() before loading an audio");
            }

            if (audioData != null) {
                PlayerFixedTimer.getInstance().start();
                currentAudioData = audioData;
                Log.success("Loading audio: " + currentAudioData.getFile().getPath());
                _AudioPlayer.load(audioData);
                isLoaded = true;
                currentTime = Duration.ZERO;
                TileManager.setActiveAudioTiles(currentAudioData);
                AudioQueue.getInstance().setActiveAudio(audioData);
                if (!Aphrodite.getInstance().getWindow().isFocused())
                    NotificationManager.getInstance().notifyNewPlayback(currentAudioData);
            } else {
                PlayerFixedTimer.getInstance().stop();
                currentAudioData = null;
                TileManager.setActiveAudioTiles(null);
            }
            updatePlayerComponents();
            updatePlayerButtons();
            updateAudioTileStatus();
            updateFullScreenMode();
            updateUI();
        } catch (Exception e) {
            handleError(e);
        }
    }


    private void handleError(Exception e) {
        if (e instanceof AudioControllerException)
            DialogFactory.showErrorDialog(((AudioControllerException) e).getCode() + ":" + e.getMessage());
        else
            DialogFactory.showErrorDialog(e.toString());
        Log.error(e + " \n " + Arrays.toString(e.getStackTrace()));
    }

    public void enableVisualizerSampling(boolean b) {
        try {
            isVisualizerSamplingEnabled = b;
            if (_AudioPlayer != null)
                _AudioPlayer.enableVisualizerSampling(b);
        } catch (Exception e) {
            handleError(e);
        }
    }

    public synchronized void play() {
        try {
            if (currentAudioData != null) {
                Log.info("playing: " + currentAudioData.getName());
                _AudioPlayer.play();
                isPaused = false;
                playerComponents.getPlayButton().setActive(isPaused);
            }
        } catch (Exception e) {
            handleError(e);
        }
    }

    public synchronized void pause() {
        Log.warn("Pausing music!");
        if (isLoaded && !isPaused) {
            try {
                _AudioPlayer.pause();
                isPaused = true;
//              TileManager.setActiveAudioTile(null);
                updatePlayerButtons();
                playerComponents.getPlayButton().setActive(isPaused);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public synchronized void stop() {
        try {
            pause();
            PlayerFixedTimer.getInstance().stop();

            if (getPlayerComponents() != null) {
                PlaybackBar bar = getPlayerComponents().getPlaybackBar();
                if (bar != null) {
                    TileManager.setActiveAudioTiles(null);
                    bar.setCurrentTime(0);
                    bar.setTotalTime(0);
                }
            }
            load(null);
        } catch (Exception e) {
            Log.error(e);
        }
    }

    public synchronized void restart() {
        try {
            _AudioPlayer.seek(Duration.ZERO);
        } catch (Exception e) {
            handleError(e);
        }

    }

    private synchronized void next() {
        try {
            AudioData audioData = AudioQueue.getInstance().getNextAudio();
            load(audioData);
            if (audioData != null) {
                if (!isPaused)
                    play();
            } else {
                stop();
            }
        } catch (Exception e) {
            handleError(e);
        }
    }

    private synchronized void previous() {
        try {

            AudioData audioData = AudioQueue.getInstance().getPrevAudio();
            load(audioData);
            if (audioData != null) {
                if (!isPaused)
                    play();
            } else
                stop();


        } catch (Exception e) {
            handleError(e);
        }
    }

    private void setVolume(double newVolume) {
        try {
            if (VOLUME != newVolume) {
                VOLUME = Math.max(0, Math.min(1, newVolume));
                _AudioPlayer.setVolume(newVolume);
                playerComponents.getVolumeButton().setVolume(newVolume);
            }
        } catch (Exception e) {
            handleError(e);
        }
    }

    private void updateFullScreenMode() {
        FullscreenPanel.getInstance().setAudio(getCurrentAudioData());
    }

    private void updateUI() {
        try {
            if (StartupSettings.DYNAMIC_THEMING_ENABLED) {
                if (UiUpdateTask != null) {
                    UiUpdateTask.join();
                    UiUpdateTask = null;
                }

                UiUpdateTask = Thread.startVirtualThread(() -> {
                    try {
                        if (currentAudioData != null) {
                            Image tempImg;
                            currentAudioData.getArtwork();
                            tempImg = currentAudioData.getArtwork();
                            BufferedImage bufferedImage = MaterialGraphics.getBufferedImageFast(tempImg);
                            if (bufferedImage != null) {
                                Color color = DynamicColors.getClosestColor(ColorUtils.getAverageColor(bufferedImage), ThemeManager.getInstance().getThemeType());
                                if (!color.equals(ThemeColors.getAccent()))
                                    ThemeManager.getInstance().setForcedAccentColor(color);
                            } else
                                ThemeManager.getInstance().setForcedAccentColor(null);

                        } else
                            ThemeManager.getInstance().setForcedAccentColor(null);
                    } catch (Exception e) {
                        handleError(e);
                    }
                });
            }
        } catch (Exception e) {
            handleError(e);
        }
    }


    private synchronized void updateAudioTileStatus() {

    }

    private synchronized void mediaEnded() {
        try {
            Log.warn("Media ended");
            Log.warn("Is media Playing: " + !isPaused);
            if (!isPaused) {
                switch (repeatMode) {
                    case REPEAT_ONE -> restart();
                    case REPEAT_ALL -> next();
                    case NO_REPEAT -> pause();
                }
                updatePlayerButtons();
            }
        } catch (Exception e) {
            handleError(e);
        }
    }


    private synchronized void shuffle() {
        try {
            AudioQueue.getInstance().shuffle();

        } catch (Exception e) {
            handleError(e);
        }
    }

    private synchronized void unShuffle() {
        try {
            AudioQueue.getInstance().unShuffle();
        } catch (Exception e) {
            handleError(e);
        }
    }

//    private synchronized void throwError(MediaException mediaException) {
//        Log.error("Error occurred while playing audio: " + mediaException.getLocalizedMessage());
//        switch (mediaException.getType()) {
//            case MEDIA_UNAVAILABLE:
//            case PLAYBACK_ERROR:
//                if (currentAudioData != null) {
//                    TileManager.removeAudioTileAsync(currentAudioData, true);
//                    DialogFactory.showErrorDialog(mediaException.getLocalizedMessage());
//                }
//                load(null);
//                break;
//            default:
//                DialogFactory.showErrorDialog(mediaException.getLocalizedMessage());
//        }
//        TileManager.setActiveAudioTiles(null);
//    }

    /**
     * Sets player components that are updated dynamically by the AudioPlayer
     */
    public void installPlayerComponents(@NotNull PlayerComponents playerComponents) {
        try {

            //Seekbar
            playerComponents.getPlaybackBar().onSeek(newCurrentTime -> {
                _AudioPlayer.seek(newCurrentTime);
                currentTime = newCurrentTime;

            });
            //Play/Pause button
            PlayButton playButton = playerComponents.getPlayButton();
            playButton.addLeftClickListener(inputEvent -> {
                if (isPaused)
                    play();
                else
                    pause();

            });
            //volume button
            playerComponents.getVolumeButton().onVolumeChange(this::setVolume);

            //like button
            playerComponents.getLikeButton().addLeftClickListener(e -> {
                setFavorite(playerComponents.getLikeButton().isActive());
            });
            //next button
            playerComponents.getNextButton().addLeftClickListener(e -> {
                next();
            });
            //previous button
            playerComponents.getPrevButton().addLeftClickListener(e -> {
                previous();
            });
            //shuffle button
            ShuffleButton shuffleButton = playerComponents.getShuffleButton();
            shuffleButton.addLeftClickListener(e -> {
                if (shuffleButton.isActive())
                    shuffle();
                else unShuffle();
            });

            LikeButton likeButton = playerComponents.getLikeButton();
            likeButton.addLeftClickListener(e -> {
                setFavorite(!likeButton.isActive());
            });

            this.playerComponents = playerComponents;

            if (isLoaded) {
                updatePlayerComponents();
                updatePlayerButtons();
            }
        } catch (Exception e) {
            handleError(e);
        }
    }


    private synchronized void setFavorite(boolean b) {
        try {

            AudioDataIndexer audioDataIndexer = AudioDataIndexer.getInstance();
            if (b)
                audioDataIndexer.addAudioFileToFavorites(currentAudioData);
            else
                audioDataIndexer.removeAudioFileFromFavorites(currentAudioData);
        } catch (Exception e) {
            handleError(e);
        }
    }

    private synchronized void updatePlayerComponents() {
        SwingUtilities.invokeLater(() -> {
            try {

                if (playerComponents != null) {
                    AudioInfoViewer viewer = playerComponents.getAudioInfoViewer();
                    VolumeButton volumeButton = playerComponents.getVolumeButton();
                    viewer.setAudio(currentAudioData);
                    Log.info("PLayer volume : " + _AudioPlayer.getVolume());
                    volumeButton.setVolume(VOLUME);

                }
            } catch (Exception e) {
                handleError(e);
            }
        });
    }


    private synchronized void updatePlayerButtons() {
        try {

            if (playerComponents != null) {
                SwingUtilities.invokeLater(() -> {
                    PlayButton playButton = playerComponents.getPlayButton();

                    if (playButton != null) {
                        playButton.setPaused(isPaused);
                    }

                    if (currentAudioData != null) {
                        LikeButton likeButton = playerComponents.getLikeButton();
                        likeButton.setActive(currentAudioData.isFavorite());
                    }
                });
            }
        } catch (Exception e) {
            handleError(e);

        }
    }

    /**
     * After a player controller has been disposed it cannot be used again
     */
    public synchronized void dispose() {
//        PlayerFixedTimer.getInstance().stop();
        try {
            PlayerFixedTimer.getInstance().dispose();
            _AudioPlayer.dispose();
        } catch (Exception e) {
            Log.error("Error occurred while disposing: " + e);
        }
    }

    private void tick() {
        try {

            if (!isSeeking) {
                if (playerComponents != null && !_AudioPlayer.isDisposed()) {
                    PlaybackBar playbackBar = playerComponents.getPlaybackBar();
                    long currentTimeTemp = _AudioPlayer.getCurrentTimeNanos();
                    SwingUtilities.invokeLater(() -> {
                        playbackBar.setCurrentTime(currentTimeTemp);
                        currentTime = Duration.ofNanos(currentTimeTemp);
                    });
                    if (_AudioPlayer.getTotalTimeNanos() != 0) {
                        if (playbackBar.getTotalTimeNanos() != _AudioPlayer.getTotalTimeNanos())
                            SwingUtilities.invokeLater(() -> {
                                playbackBar.setTotalTime(_AudioPlayer.getTotalTimeNanos());
                            });
                        if (_AudioPlayer.getTotalTimeNanos() > 0 && currentAudioData.getDurationInSeconds() != _AudioPlayer.getTotalTimeNanos() / 1e9) {
                            currentAudioData.setDurationInSeconds(_AudioPlayer.getTotalTimeNanos() / 1e9);
                            TileManager.getMappedTiles(currentAudioData).forEach(Component::repaint);
                        }

                    }
                }
            }
        } catch (Exception e) {
            handleError(e);
        }
    }


    public @Nullable PlayerComponents getPlayerComponents() {
        return playerComponents;
    }

    public AudioData getCurrentAudioData() {
        return currentAudioData;
    }

    public boolean isPaused() {
        return isPaused;
    }

    public boolean isVisualizerSamplingEnabled() {
        return isVisualizerSamplingEnabled;
    }

    private static final class PlayerFixedTimer extends MaterialFixedTimer {
        private static PlayerFixedTimer instance;
        private static final int timerPeriod = 50;

        private PlayerFixedTimer() {
            super(timerPeriod);
        }

        static PlayerFixedTimer getInstance() {
            if (instance == null)
                instance = new PlayerFixedTimer();
            return instance;
        }

        @Override
        public void tick(float e) {
            AphroditeAudioController.getInstance().tick();
        }
    }


}
