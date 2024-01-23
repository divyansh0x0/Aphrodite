package app.audio.search;

import app.audio.AudioData;
import app.audio.indexer.AudioDataIndexer;
import app.components.listeners.SearchCompletedListener;
import app.local.cache.FileCacheManager;
import app.settings.StartupSettings;
import material.utils.Log;
import material.utils.OsUtils;
import org.jetbrains.annotations.NotNull;

import javax.swing.filechooser.FileSystemView;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// TODO Add reload on pressing CTRL+R
// TODO Save last search i.e. undo and redo
public class SystemSearch {
    public static final int NUM_THREADS = Runtime.getRuntime().availableProcessors();
    private static final ArrayList<SearchCompletedListener> searchCompletedListeners = new ArrayList<>();
    private static SystemSearch instance;
    private static FileSystemView FSV = FileSystemView.getFileSystemView();
    private int SEARCH_DEPTH = StartupSettings.SEARCH_DEPTH;
    private boolean isSearchOnceComplete = false;
    private boolean isSearching = false;
    private boolean isBackgroundSearchRunning = false;
    private Thread searchThread;

    private SystemSearch() {
//        forceSearch();
        Runtime.getRuntime().addShutdownHook(new Thread(()->{
            if(searchThread != null && searchThread.isAlive())
                searchThread.interrupt();
        }));
    }

    public void forceSearch() {
        if(isSearching){
            searchThread.interrupt();
            searchThread = null;
            isSearching = false;
        }
        Log.warn("Starting system search");
            searchThread = new Thread(this::search);
            searchThread.setName("System Search Thread");
            searchThread.start();
    }

    private void search() {
        isSearching = true;
        if (cacheLoaded()) {
            searchCompleted();
            isBackgroundSearchRunning = true;
        }
        switch (OsUtils.getOsType()) {
            case LINUX, MAC -> rootFSSearch();
            case WINDOWS -> windowsFSSearch();
        }

    }

    /**
     * Loads cache and checks if all files were loaded
     *
     * @return if all files in the cache were loaded
     */
    private synchronized boolean cacheLoaded() {
        List<File> files = FileCacheManager.getInstance().getCachedFiles();
        int validFiles = 0;

        long t1 = System.nanoTime();

        ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        ArrayList<Callable<Void>> tasks = new ArrayList<>();
        for (File file : files) {
            Callable<Void> callable= () -> {
                if (file.exists() && AudioData.isValidAudio(file.toPath())) {
                    AudioData audioData = new AudioData(file);
                    AudioDataIndexer.getInstance().addAudioFile(audioData);
                } else {
                    FileCacheManager.getInstance().deleteCacheFile(file);
                }
                return null;
            };

            validFiles++;
            tasks.add(callable);
        }
        try {
            executorService.invokeAll(tasks);
        }catch (Exception e){
            Log.error(e);
        }
        long t2 = System.nanoTime();
        Log.success("Time taken to register artworks: " + ((t2 - t1)/0.000_0001)+ "ms");
        return !files.isEmpty() && validFiles == files.size();
    }


    private void rootFSSearch() {
        Log.warn("Linux/Mac File System detected");
        File root = FSV.getHomeDirectory();
        AudioFileVisitor fileVisitor = new AudioFileVisitor();
        try {
            Files.walkFileTree(root.toPath(), EnumSet.of(FileVisitOption.FOLLOW_LINKS), SEARCH_DEPTH, fileVisitor);
            List<File> arr = fileVisitor.getAudioFileArrayList();
            saveDataAsync(arr);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        searchCompleted();
    }


    private void windowsFSSearch() {
        Log.warn("Windows File System detected");
        File[] roots = File.listRoots();
        AudioFileVisitor fileVisitor = new AudioFileVisitor();
        for (File root : roots) {
            try {
                Files.walkFileTree(root.toPath(), EnumSet.of(FileVisitOption.FOLLOW_LINKS), SEARCH_DEPTH, fileVisitor);
                List<File> arr = fileVisitor.getAudioFileArrayList();
                Log.success("Number of mp3 files found: " + arr.size());
                saveDataAsync(arr);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        searchCompleted();
    }

    private synchronized void saveData(List<File> files) {
        for (File file : files) {
            AudioDataIndexer.getInstance().addAudioFile(new AudioData(file));
            FileCacheManager.getInstance().cacheFile(file);
        }
        FileCacheManager.getInstance().saveCacheToStorage();
    }

    private void saveDataAsync(List<File> files) {
        Log.success("Number of MP3 files found:" + files.size());
        ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        ArrayList<Callable<Void>> tasks = getSavingTasks(files);
        try {
            executorService.invokeAll(tasks);
        }catch (Exception e){
            Log.error(e);
        }
        executorService.shutdown();
        FileCacheManager.getInstance().saveCacheToStorage();
        files.clear();
    }

    @NotNull
    private static ArrayList<Callable<Void>> getSavingTasks(List<File> files) {
        ArrayList<Callable<Void>> tasks = new ArrayList<>();
        for (File file : files) {
            Callable<Void> callable= () -> {
                if (file.exists() && AudioData.isValidAudio(file.toPath())) {
                    AudioData audioData = new AudioData(file);
                    AudioDataIndexer.getInstance().addAudioFile(audioData);
                } else {
                    FileCacheManager.getInstance().deleteCacheFile(file);
                }
                return null;
            };
            tasks.add(callable);
        }
        return tasks;
    }


    public static SystemSearch getInstance() {
        if (instance == null)
            instance = new SystemSearch();
        return instance;
    }

    public int getSearchDepth() {
        return SEARCH_DEPTH;
    }

    public SystemSearch setSearchDepth(int SEARCH_DEPTH) {
        this.SEARCH_DEPTH = SEARCH_DEPTH;
        return this;
    }


    public void onSearchComplete(SearchCompletedListener listener) {
        if (!searchCompletedListeners.contains(listener))
            searchCompletedListeners.add(listener);
    }

    private synchronized void searchCompleted() {
        Log.success("SEARCH COMPLETED!");
        AudioDataIndexer.getInstance().indexAndSortAudioFiles();

        isSearchOnceComplete = true;
        isSearching = false;
        isBackgroundSearchRunning = false;
        for (SearchCompletedListener l : searchCompletedListeners) {
            l.searchComplete();
        }
        AudioDataIndexer.getInstance().callIndexUpdated();
    }

    public boolean isSearchOnceComplete() {
        return isSearchOnceComplete;
    }

    public boolean isBackgroundSearchRunning() {
        return isBackgroundSearchRunning;
    }
}
