package inlinenotes;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.event.EditorMouseEvent;
import com.intellij.openapi.editor.event.EditorMouseEventArea;
import com.intellij.openapi.editor.event.EditorMouseListener;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;

import javax.swing.Icon;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseEvent;
import java.awt.font.FontRenderContext;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.text.AttributedString;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class NoteInlays {
    private static final Key<EditorInteractions> LISTENING = Key.create("inlinenotes.cardListeners");
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy, HH:mm", Locale.ROOT);

    private NoteInlays() {}

    static String dateHeader(NoteStore.Comment comment) {
        long timestamp = comment == null ? System.currentTimeMillis()
                : comment.updatedAt != 0 ? comment.updatedAt : comment.createdAt;
        return timestamp == 0 ? "" : DATE_FORMAT.format(
                Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()));
    }

    static Font cardFont() {
        Font font = UIManager.getFont("Label.font");
        return font == null ? new Font(Font.SANS_SERIF, Font.PLAIN, JBUI.scale(13))
                : font.deriveFont(Font.PLAIN);
    }

    static int cardWidth(Editor editor, NoteStore.Comment comment) {
        int viewport = editor.getContentComponent().getVisibleRect().width;
        if (viewport <= 0) viewport = JBUI.scale(560);
        FontMetrics header = editor.getContentComponent().getFontMetrics(cardFont().deriveFont(Font.BOLD));
        int minimum = header.stringWidth(dateHeader(comment)) + 2 * JBUI.scale(20)
                + AllIcons.Actions.Edit.getIconWidth() + AllIcons.General.Delete.getIconWidth() + JBUI.scale(28);
        return Math.max(minimum, Math.min(JBUI.scale(560), viewport - JBUI.scale(12)));
    }

    static Color cardBackground() {
        return JBColor.namedColor("InlineNotes.Card.background", new JBColor(0xF6F8FA, 0x26292E));
    }

    static Color cardBorder() {
        return JBColor.namedColor("InlineNotes.Card.border", new JBColor(0xD0D7DE, 0x444A53));
    }

    static Color cardForeground() {
        return JBColor.namedColor("Label.foreground", new JBColor(0x24292F, 0xD6D9DE));
    }

    static void paintCardFrame(Graphics2D graphics, int width, int height) {
        int margin = JBUI.scale(6);
        int radius = JBUI.scale(12);
        RoundRectangle2D card = new RoundRectangle2D.Double(margin + 0.5, margin + 0.5,
                width - 2 * margin - 1, height - 2 * margin - 1, radius, radius);
        graphics.setColor(cardBackground());
        graphics.fill(card);
        graphics.setColor(cardBorder());
        graphics.draw(card);
    }

    static void show(Project project, VirtualFile file) {
        show(project, file, -1);
    }

    static void show(Project project, VirtualFile file, int revealLine) {
        NoteStore store = project.getService(NoteStore.class);
        if (store.isDisposed()) return;
        String path = NoteStore.relativePath(project, file);
        if (path == null) return;
        List<NoteStore.Comment> revealed = revealLine < 0 ? List.of() : store.commentsFor(path).stream()
                .filter(comment -> store.currentLine(comment) == revealLine).toList();
        FileEditorManager manager = FileEditorManager.getInstance(project);
        for (FileEditor fileEditor : manager.getAllEditors(file)) {
            if (fileEditor instanceof TextEditor textEditor) {
                Editor editor = textEditor.getEditor();
                manager.runWhenLoaded(editor, () -> {
                    if (!store.isDisposed() && !project.isDisposed() && !editor.isDisposed() && file.isValid()) {
                        NoteGutters.install(editor, store, path).reveal(revealed);
                        render(editor, store, path);
                    }
                });
            }
        }
    }

    static void render(Editor editor, NoteStore store, String path) {
        listen(editor, store);
        NoteGutters gutters = NoteGutters.install(editor, store, path);
        NoteGutters.refresh(editor);
        InlineNoteEditor.reconcile(editor, gutters);
        Set<NoteStore.Comment> rendered = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var inlay : editor.getInlayModel().getBlockElementsInRange(
                0, editor.getDocument().getTextLength(), CommentRenderer.class)) {
            CommentRenderer renderer = inlay.getRenderer();
            if (gutters.isHidden(renderer.comment) || InlineNoteEditor.isEditing(editor, renderer.comment)) inlay.dispose();
            else rendered.add(renderer.comment);
        }
        for (NoteStore.Comment comment : store.commentsFor(path)) {
            if (rendered.contains(comment) || gutters.isHidden(comment) || InlineNoteEditor.isEditing(editor, comment)) continue;
            RangeMarker marker = store.markerFor(comment, editor.getDocument());
            if (marker != null && marker.isValid()) {
                editor.getInlayModel().addBlockElement(
                        marker.getStartOffset(), true, false, 0, new CommentRenderer(comment));
            }
        }
    }

    static void reveal(Editor editor, NoteStore store, String path, int line) {
        NoteGutters.install(editor, store, path).revealLine(line);
        render(editor, store, path);
    }

    private static List<Inlay<? extends CommentRenderer>> cards(Editor editor) {
        return editor.getInlayModel().getBlockElementsInRange(
                0, editor.getDocument().getTextLength(), CommentRenderer.class);
    }

    private static void listen(Editor editor, NoteStore store) {
        if (editor.getUserData(LISTENING) != null) return;
        EditorInteractions listener = new EditorInteractions(editor, store);
        editor.putUserData(LISTENING, listener);
        editor.addEditorMouseListener(listener);
        editor.getComponent().addComponentListener(listener);
    }

    static void dispose(Project project) {
        FileEditorManager manager = project.getServiceIfCreated(FileEditorManager.class);
        if (manager == null) return;
        for (FileEditor fileEditor : manager.getAllEditors()) {
            if (fileEditor instanceof TextEditor textEditor && !textEditor.getEditor().isDisposed()) {
                Editor editor = textEditor.getEditor();
                InlineNoteEditor.discard(editor);
                NoteGutters.dispose(editor);
                for (var inlay : cards(editor)) inlay.dispose();
                EditorInteractions listener = editor.getUserData(LISTENING);
                if (listener != null) {
                    editor.removeEditorMouseListener(listener);
                    editor.getComponent().removeComponentListener(listener);
                    editor.putUserData(LISTENING, null);
                }
            }
        }
    }

    static void delete(Project project, NoteStore.Comment comment) {
        project.getService(NoteStore.class).remove(comment);
        for (FileEditor fileEditor : FileEditorManager.getInstance(project).getAllEditors()) {
            if (fileEditor instanceof TextEditor textEditor && !textEditor.getEditor().isDisposed()) {
                InlineNoteEditor.discard(textEditor.getEditor(), comment);
                for (var inlay : cards(textEditor.getEditor())) {
                    CommentRenderer renderer = inlay.getRenderer();
                    if (renderer.comment == comment) inlay.dispose();
                }
                NoteGutters.refresh(textEditor.getEditor());
            }
        }
    }

    static void clearAll(Project project) {
        project.getService(NoteStore.class).clearAll();
        for (FileEditor fileEditor : FileEditorManager.getInstance(project).getAllEditors()) {
            if (fileEditor instanceof TextEditor textEditor && !textEditor.getEditor().isDisposed()) {
                InlineNoteEditor.discard(textEditor.getEditor());
                for (var inlay : cards(textEditor.getEditor())) inlay.dispose();
                NoteGutters.refresh(textEditor.getEditor());
            }
        }
    }

    static void commit(Project project, NoteStore.Comment comment, String text) {
        NoteStore store = project.getService(NoteStore.class);
        if (store.isDisposed() || project.isDisposed()) return;
        store.edit(comment, text);
        for (FileEditor fileEditor : FileEditorManager.getInstance(project).getAllEditors()) {
            if (fileEditor instanceof TextEditor textEditor && !textEditor.getEditor().isDisposed()) {
                for (var inlay : cards(textEditor.getEditor())) {
                    CommentRenderer renderer = inlay.getRenderer();
                    if (renderer.comment == comment) inlay.update();
                }
                NoteGutters.refresh(textEditor.getEditor());
            }
        }
    }

    private static final class EditorInteractions extends ComponentAdapter implements EditorMouseListener {
        private final Editor editor;
        private final NoteStore store;

        EditorInteractions(Editor editor, NoteStore store) {
            this.editor = editor;
            this.store = store;
        }

        @Override
        public void componentResized(ComponentEvent event) {
            if (!store.isDisposed() && !editor.isDisposed()) {
                cards(editor).forEach(Inlay::update);
                InlineNoteEditor.resize(editor);
            }
        }

        @Override
        public void mouseClicked(EditorMouseEvent event) {
            if (store.isDisposed()) return;
            MouseEvent mouse = event.getMouseEvent();
            if (event.isConsumed() || mouse.isConsumed() || !SwingUtilities.isLeftMouseButton(mouse)
                    || mouse.getClickCount() != 1 || event.getArea() != EditorMouseEventArea.EDITING_AREA) return;
            Project project = editor.getProject();
            if (project == null || project.isDisposed() || editor.isDisposed()) return;
            Point point = SwingUtilities.convertPoint(
                    mouse.getComponent(), mouse.getPoint(), editor.getContentComponent());
            for (var inlay : cards(editor)) {
                Rectangle bounds = inlay.getBounds();
                if (bounds == null || !bounds.contains(point)) continue;
                CommentRenderer renderer = inlay.getRenderer();
                CardLayout layout = renderer.layout(editor, inlay.getWidthInPixels());
                Point local = new Point(point.x - bounds.x, point.y - bounds.y);
                boolean edit = layout.editBounds().contains(local);
                if (edit || layout.deleteBounds().contains(local)) {
                    event.consume();
                    mouse.consume();
                    if (edit) InlineNoteEditor.open(project, editor, store, renderer.comment);
                    else delete(project, renderer.comment);
                    return;
                }
            }
        }
    }

    private record CardLayout(Font font, Font headerFont, List<TextLayout> lines, int width, int height,
                              int contentX, int headerBaseline, int bodyBaseline, int lineHeight,
                              Rectangle editBounds, Rectangle deleteBounds) {}

    private static final class CommentRenderer implements EditorCustomElementRenderer {
        private static final Icon EDIT = AllIcons.Actions.Edit;
        private static final Icon DELETE = AllIcons.General.Delete;
        private final NoteStore.Comment comment;

        CommentRenderer(NoteStore.Comment comment) {
            this.comment = comment;
        }

        private String header() {
            return dateHeader(comment);
        }

        private Font font() {
            return cardFont();
        }

        @Override
        public int calcWidthInPixels(Inlay inlay) {
            return cardWidth(inlay.getEditor(), comment);
        }

        @Override
        public int calcHeightInPixels(Inlay inlay) {
            return layout(inlay.getEditor(), calcWidthInPixels(inlay)).height();
        }

        private CardLayout layout(Editor editor, int width) {
            Font font = font();
            Font headerFont = font.deriveFont(Font.BOLD);
            FontMetrics metrics = editor.getContentComponent().getFontMetrics(font);
            FontMetrics headerMetrics = editor.getContentComponent().getFontMetrics(headerFont);
            int inset = JBUI.scale(20);
            int gap = JBUI.scale(10);
            List<TextLayout> lines = wrap(font, metrics.getFontRenderContext(), width - 2 * inset);
            int padding = JBUI.scale(4);
            int actionHeight = Math.max(EDIT.getIconHeight(), DELETE.getIconHeight()) + 2 * padding;
            Rectangle deleteBounds = new Rectangle(width - inset - DELETE.getIconWidth() - 2 * padding,
                    inset, DELETE.getIconWidth() + 2 * padding, actionHeight);
            Rectangle editBounds = new Rectangle(deleteBounds.x - JBUI.scale(4) - EDIT.getIconWidth() - 2 * padding,
                    inset, EDIT.getIconWidth() + 2 * padding, actionHeight);
            int headerHeight = Math.max(headerMetrics.getHeight(), actionHeight);
            int bodyTop = inset + headerHeight + gap;
            int height = bodyTop + lines.size() * metrics.getHeight() + inset;
            int headerBaseline = inset + (headerHeight - headerMetrics.getHeight()) / 2 + headerMetrics.getAscent();
            return new CardLayout(font, headerFont, lines, width, height,
                    inset, headerBaseline, bodyTop + metrics.getAscent(),
                    metrics.getHeight(), editBounds, deleteBounds);
        }

        private List<TextLayout> wrap(Font font, FontRenderContext context, int width) {
            List<TextLayout> lines = new ArrayList<>();
            for (String paragraph : comment.text.split("\\R", -1)) {
                if (paragraph.isEmpty()) {
                    lines.add(new TextLayout(" ", font, context));
                    continue;
                }
                AttributedString text = new AttributedString(paragraph);
                text.addAttribute(TextAttribute.FONT, font);
                LineBreakMeasurer measurer = new LineBreakMeasurer(text.getIterator(), context);
                while (measurer.getPosition() < paragraph.length()) lines.add(measurer.nextLayout(width));
            }
            return lines;
        }

        @Override
        public void paint(Inlay inlay, Graphics2D original, Rectangle2D region, TextAttributes attributes) {
            CardLayout layout = layout(inlay.getEditor(), inlay.getWidthInPixels());
            Graphics2D graphics = (Graphics2D) original.create();
            try {
                graphics.translate(region.getX(), region.getY());
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                paintCardFrame(graphics, layout.width(), layout.height());
                graphics.setColor(cardForeground());
                graphics.setFont(layout.headerFont());
                graphics.drawString(header(), layout.contentX(), layout.headerBaseline());
                graphics.setFont(layout.font());
                int y = layout.bodyBaseline();
                for (TextLayout line : layout.lines()) {
                    line.draw(graphics, layout.contentX(), y);
                    y += layout.lineHeight();
                }
                paintIcon(EDIT, inlay.getEditor(), graphics, layout.editBounds());
                paintIcon(DELETE, inlay.getEditor(), graphics, layout.deleteBounds());
            } finally {
                graphics.dispose();
            }
        }

        private void paintIcon(Icon icon, Editor editor, Graphics2D graphics, Rectangle bounds) {
            icon.paintIcon(editor.getContentComponent(), graphics,
                    bounds.x + (bounds.width - icon.getIconWidth()) / 2,
                    bounds.y + (bounds.height - icon.getIconHeight()) / 2);
        }
    }
}
