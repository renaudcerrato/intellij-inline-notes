package inlinenotes;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.Comparator;

final class ReviewClipboard {
    private ReviewClipboard() {}

    static void copy(Project project) {
        String text = ReadAction.compute(() -> snapshot(project));
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        } catch (IllegalStateException exception) {
            Messages.showInfoMessage(project, "Clipboard is unavailable. Please try again.", "Inline Notes");
        }
    }

    static VirtualFile findFile(Project project, String path) {
        String basePath = project.getBasePath();
        VirtualFile base = basePath == null ? null : LocalFileSystem.getInstance().findFileByPath(basePath);
        VirtualFile file = base == null ? null : base.findFileByRelativePath(path);
        return file != null && file.isValid() && !file.isDirectory()
                && path.equals(NoteStore.relativePath(project, file)) ? file : null;
    }

    private static String snapshot(Project project) {
        NoteStore store = project.getService(NoteStore.class);
        StringBuilder text = new StringBuilder();
        var comments = store.allComments().stream()
                .sorted(Comparator.comparing((NoteStore.Comment comment) -> comment.path)
                        .thenComparingInt(store::currentLine))
                .toList();
        int number = 1;
        for (NoteStore.Comment comment : comments) {
            if (!text.isEmpty()) text.append("\n\n");
            text.append("## Comment ").append(number++).append(" — ")
                    .append(comment.path).append(':').append(store.currentLine(comment) + 1)
                    .append("\n\n").append(comment.text);
        }
        return text.toString();
    }
}
