package inlinenotes;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

public final class AddInlineNoteAction extends DumbAwareAction {
    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        VirtualFile file = editor == null ? null
                : FileDocumentManager.getInstance().getFile(editor.getDocument());
        event.getPresentation().setEnabledAndVisible(project != null && !project.isDisposed()
                && editor != null && !editor.isDisposed() && file != null && file.isValid()
                && NoteStore.relativePath(project, file) != null);
    }

    @Override
    public void actionPerformed(AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        if (project == null || project.isDisposed() || editor == null || editor.isDisposed()) return;
        add(project, editor, editor.getDocument().getLineNumber(editor.getCaretModel().getOffset()));
    }

    static void add(Project project, Editor editor, int line) {
        if (project.isDisposed() || editor.isDisposed() || line < 0 || line >= editor.getDocument().getLineCount()) return;
        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (file == null || !file.isValid()) return;
        String path = NoteStore.relativePath(project, file);
        if (path == null) return;
        NoteStore store = project.getService(NoteStore.class);
        InlineNoteEditor.openAdd(project, editor, store, file, path, line);
    }
}
