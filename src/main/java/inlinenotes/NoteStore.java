package inlinenotes;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.RoamingType;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

@Service(Service.Level.PROJECT)
@State(name = "InlineNotes", reloadable = false,
        storages = @Storage(value = "inline-notes.xml", roamingType = RoamingType.DISABLED))
public final class NoteStore implements PersistentStateComponent<NoteStore.StoredState>, Disposable {
    public static final class StoredState {
        public List<Comment> comments = new ArrayList<>();
    }

    public static final class Comment {
        public String path = "";
        public int line;
        public String text = "";
        public long createdAt;
        public long updatedAt;

        public Comment() {}

        Comment(String path, int line, String text, long createdAt, long updatedAt) {
            this.path = path;
            this.line = line;
            this.text = text;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }
    }

    private final List<Comment> comments = new ArrayList<>();
    private final Map<Comment, RangeMarker> markers = new IdentityHashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private final Project project;
    private volatile boolean disposed;

    public NoteStore(Project project) {
        this.project = project;
    }

    boolean isDisposed() {
        return disposed;
    }

    void initialize() {
        if (disposed) return;
        for (VirtualFile file : FileEditorManager.getInstance(project).getOpenFiles()) {
            NoteInlays.show(project, file);
        }
    }

    static String relativePath(Project project, VirtualFile file) {
        String basePath = project.getBasePath();
        if (basePath == null || !file.isInLocalFileSystem() || file.isDirectory()) return null;
        Path base = Path.of(basePath).normalize();
        Path path = Path.of(file.getPath()).normalize();
        return path.startsWith(base) ? base.relativize(path).toString().replace('\\', '/') : null;
    }

    void add(String path, Document document, int line, String text) {
        synchronized (this) {
            long timestamp = System.currentTimeMillis();
            Comment comment = new Comment(path, line, text, timestamp, timestamp);
            comments.add(comment);
            markerFor(comment, document);
        }
        notifyChanged();
    }

    void remove(Comment comment) {
        synchronized (this) {
            if (!comments.remove(comment)) return;
            RangeMarker marker = markers.remove(comment);
            if (marker != null) marker.dispose();
        }
        notifyChanged();
    }

    void edit(Comment comment, String text) {
        synchronized (this) {
            if (!comments.contains(comment) || comment.text.equals(text)) return;
            comment.text = text;
            comment.updatedAt = System.currentTimeMillis();
        }
        notifyChanged();
    }

    void clearAll() {
        synchronized (this) {
            if (comments.isEmpty()) return;
            comments.clear();
            resetMarkers();
        }
        notifyChanged();
    }

    synchronized List<Comment> allComments() {
        return List.copyOf(comments);
    }

    int currentLine(Comment comment) {
        return ReadAction.compute(() -> {
            synchronized (this) {
                RangeMarker marker = markers.get(comment);
                if (marker != null) updateLine(comment, marker);
                return comment.line;
            }
        });
    }

    synchronized Disposable addListener(Runnable listener) {
        listeners.add(listener);
        return () -> {
            synchronized (NoteStore.this) {
                listeners.remove(listener);
            }
        };
    }

    private void notifyChanged() {
        List<Runnable> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(listeners);
        }
        snapshot.forEach(Runnable::run);
    }

    synchronized List<Comment> commentsFor(String path) {
        return comments.stream().filter(comment -> comment.path.equals(path)).toList();
    }

    synchronized RangeMarker markerFor(Comment comment, Document document) {
        RangeMarker marker = markers.get(comment);
        if (marker != null) return marker;
        if (comment.line >= document.getLineCount()) return null;
        int offset = document.getLineEndOffset(comment.line);
        marker = document.createRangeMarker(offset, offset, true);
        marker.setGreedyToLeft(true);
        markers.put(comment, marker);
        return marker;
    }

    synchronized void fileClosed(String path) {
        for (Comment comment : commentsFor(path)) {
            RangeMarker marker = markers.remove(comment);
            if (marker != null) {
                updateLine(comment, marker);
                marker.dispose();
            }
        }
    }

    private static void updateLine(Comment comment, RangeMarker marker) {
        if (marker.isValid()) {
            comment.line = marker.getDocument().getLineNumber(marker.getStartOffset());
        }
    }

    @Override
    public StoredState getState() {
        return ReadAction.compute(() -> {
            synchronized (this) {
                StoredState state = new StoredState();
                for (Comment comment : comments) {
                    RangeMarker marker = markers.get(comment);
                    if (marker != null) updateLine(comment, marker);
                    state.comments.add(new Comment(comment.path, comment.line, comment.text,
                            comment.createdAt, comment.updatedAt));
                }
                return state;
            }
        });
    }

    @Override
    public synchronized void loadState(StoredState state) {
        resetMarkers();
        comments.clear();
        for (Comment comment : state.comments) {
            if (comment != null && comment.path != null && !comment.path.isBlank()
                    && comment.line >= 0 && comment.text != null && !comment.text.isBlank()) {
                comments.add(comment);
            }
        }
    }

    private void resetMarkers() {
        markers.values().forEach(RangeMarker::dispose);
        markers.clear();
    }

    @Override
    public void dispose() {
        disposed = true;
        NoteInlays.dispose(project);
        synchronized (this) {
            markers.forEach(NoteStore::updateLine);
            resetMarkers();
            listeners.clear();
        }
    }
}
