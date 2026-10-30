package inlinenotes;

import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

public final class NoteEditorListener implements FileEditorManagerListener {
    @Override
    public void fileOpened(FileEditorManager manager, VirtualFile file) {
        NoteInlays.show(manager.getProject(), file);
    }

    @Override
    public void fileClosed(FileEditorManager manager, VirtualFile file) {
        Project project = manager.getProject();
        if (project.isDisposed() || manager.getAllEditors(file).length != 0) return;
        String path = NoteStore.relativePath(project, file);
        if (path != null) project.getService(NoteStore.class).fileClosed(path);
    }
}
