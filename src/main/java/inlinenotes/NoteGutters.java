package inlinenotes;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.event.EditorMouseEvent;
import com.intellij.openapi.editor.event.EditorMouseEventArea;
import com.intellij.openapi.editor.event.EditorMouseListener;
import com.intellij.openapi.editor.event.EditorMouseMotionListener;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.util.ui.JBUI;

import javax.swing.Icon;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class NoteGutters implements EditorMouseMotionListener, EditorMouseListener {
    private static final Key<NoteGutters> KEY = Key.create("inlinenotes.gutters");
    private static final Icon BUBBLE = new BubbleIcon();
    private final Editor editor;
    private final Project project;
    private final NoteStore store;
    private final String path;
    private final Set<NoteStore.Comment> hidden = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<RangeHighlighter> bubbles = new ArrayList<>();
    private RangeHighlighter hover;

    private NoteGutters(Editor editor, NoteStore store, String path) {
        this.editor = editor;
        this.project = editor.getProject();
        this.store = store;
        this.path = path;
    }

    static NoteGutters install(Editor editor, NoteStore store, String path) {
        NoteGutters gutters = editor.getUserData(KEY);
        if (gutters == null) {
            gutters = new NoteGutters(editor, store, path);
            editor.putUserData(KEY, gutters);
            editor.addEditorMouseMotionListener(gutters);
            editor.addEditorMouseListener(gutters);
        }
        return gutters;
    }

    static void refresh(Editor editor) {
        NoteGutters gutters = editor.getUserData(KEY);
        if (gutters != null && gutters.active()) gutters.refresh();
    }

    static void dispose(Editor editor) {
        NoteGutters gutters = editor.getUserData(KEY);
        if (gutters == null) return;
        editor.removeEditorMouseMotionListener(gutters);
        editor.removeEditorMouseListener(gutters);
        gutters.clearHover();
        gutters.bubbles.forEach(RangeHighlighter::dispose);
        gutters.bubbles.clear();
        gutters.hidden.clear();
        editor.putUserData(KEY, null);
    }

    private boolean active() {
        return project != null && !project.isDisposed() && !store.isDisposed() && !editor.isDisposed();
    }

    boolean isHidden(NoteStore.Comment comment) {
        return hidden.contains(comment);
    }

    void reveal(List<NoteStore.Comment> comments) {
        hidden.removeAll(comments);
    }

    void revealLine(int line) {
        reveal(commentsOnLine(line));
    }

    private int line(NoteStore.Comment comment) {
        RangeMarker marker = store.markerFor(comment, editor.getDocument());
        return marker != null && marker.isValid()
                ? editor.getDocument().getLineNumber(marker.getStartOffset()) : -1;
    }

    private List<NoteStore.Comment> commentsOnLine(int line) {
        return store.commentsFor(path).stream().filter(comment -> line(comment) == line).toList();
    }

    private void refresh() {
        clearHover();
        bubbles.forEach(RangeHighlighter::dispose);
        bubbles.clear();
        List<NoteStore.Comment> comments = store.commentsFor(path);
        hidden.retainAll(comments);
        Map<Integer, List<NoteStore.Comment>> groups = new LinkedHashMap<>();
        for (NoteStore.Comment comment : comments) {
            int line = line(comment);
            if (line >= 0) groups.computeIfAbsent(line, ignored -> new ArrayList<>()).add(comment);
        }
        for (var group : groups.entrySet()) {
            RangeHighlighter highlighter = highlighter(group.getKey());
            bubbles.add(highlighter);
            highlighter.setGutterIconRenderer(new Control(this, highlighter, group.getValue().getFirst()));
        }
    }

    private RangeHighlighter highlighter(int line) {
        return editor.getMarkupModel().addLineHighlighter(line, HighlighterLayer.ADDITIONAL_SYNTAX, null);
    }

    private void clearHover() {
        if (hover != null) {
            hover.dispose();
            hover = null;
        }
    }

    @Override
    public void mouseMoved(EditorMouseEvent event) {
        if (!active() || (event.getArea() != EditorMouseEventArea.LINE_NUMBERS_AREA
                && event.getArea() != EditorMouseEventArea.LINE_MARKERS_AREA)) {
            clearHover();
            return;
        }
        int line = event.getLogicalPosition().line;
        if (line < 0 || line >= editor.getDocument().getLineCount() || !commentsOnLine(line).isEmpty()) {
            clearHover();
            return;
        }
        if (hover != null && hover.isValid()
                && editor.getDocument().getLineNumber(hover.getStartOffset()) == line) return;
        clearHover();
        hover = highlighter(line);
        hover.setGutterIconRenderer(new Control(this, hover, null));
    }

    @Override
    public void mouseExited(EditorMouseEvent event) {
        clearHover();
    }

    private void toggle(int line) {
        List<NoteStore.Comment> comments = commentsOnLine(line);
        if (comments.isEmpty()) return;
        if (hidden.containsAll(comments)) hidden.removeAll(comments);
        else hidden.addAll(comments);
        NoteInlays.render(editor, store, path);
    }

    private static final class Control extends GutterIconRenderer implements DumbAware {
        private final NoteGutters owner;
        private final RangeHighlighter anchor;
        private final NoteStore.Comment representative;
        private final RangeMarker position;

        Control(NoteGutters owner, RangeHighlighter anchor, NoteStore.Comment representative) {
            this.owner = owner;
            this.anchor = anchor;
            this.representative = representative;
            this.position = representative == null ? anchor
                    : owner.store.markerFor(representative, owner.editor.getDocument());
        }

        private boolean active() {
            return owner.active() && anchor.isValid() && position != null && position.isValid();
        }

        private int currentLine() {
            return owner.editor.getDocument().getLineNumber(position.getStartOffset());
        }

        @Override
        public Icon getIcon() {
            return BUBBLE;
        }

        @Override
        public String getTooltipText() {
            if (!active()) return null;
            if (representative == null) return "Add Inline Note";
            List<NoteStore.Comment> comments = owner.commentsOnLine(currentLine());
            if (comments.isEmpty()) return null;
            return (owner.hidden.containsAll(comments) ? "Show " : "Hide ") + comments.size()
                    + (comments.size() == 1 ? " comment" : " comments");
        }

        @Override
        public AnAction getClickAction() {
            return new DumbAwareAction() {
                @Override
                public ActionUpdateThread getActionUpdateThread() {
                    return ActionUpdateThread.EDT;
                }

                @Override
                public void actionPerformed(AnActionEvent event) {
                    if (!active()) return;
                    int line = currentLine();
                    if (representative != null) owner.toggle(line);
                    else {
                        if (owner.commentsOnLine(line).isEmpty()) {
                            AddInlineNoteAction.add(owner.project, owner.editor, line);
                        }
                    }
                }
            };
        }

        @Override
        public boolean equals(Object object) {
            return object instanceof Control other && owner == other.owner && representative == other.representative
                    && (representative != null || anchor == other.anchor);
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(owner)
                    + System.identityHashCode(representative == null ? anchor : representative);
        }
    }

    private static final class BubbleIcon implements Icon {
        @Override
        public int getIconWidth() {
            return JBUI.scale(14);
        }

        @Override
        public int getIconHeight() {
            return JBUI.scale(14);
        }

        @Override
        public void paintIcon(Component component, Graphics original, int x, int y) {
            Graphics2D graphics = (Graphics2D) original.create();
            try {
                graphics.translate(x + JBUI.scale(1), y + JBUI.scale(1));
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                graphics.setStroke(new BasicStroke(JBUI.scale(1)));
                Color color = UIManager.getColor("Label.foreground");
                graphics.setColor(color == null ? component.getForeground() : color);
                Path2D bubble = new Path2D.Double();
                bubble.moveTo(0, 0);
                bubble.lineTo(JBUI.scale(11), 0);
                bubble.lineTo(JBUI.scale(11), JBUI.scale(8));
                bubble.lineTo(JBUI.scale(5), JBUI.scale(8));
                bubble.lineTo(JBUI.scale(2), JBUI.scale(11));
                bubble.lineTo(JBUI.scale(2), JBUI.scale(8));
                bubble.lineTo(0, JBUI.scale(8));
                bubble.closePath();
                graphics.draw(bubble);
            } finally {
                graphics.dispose();
            }
        }
    }
}
