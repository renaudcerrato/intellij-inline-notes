package inlinenotes;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.ComponentInlayKt;
import com.intellij.openapi.editor.ComponentInlayRenderer;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.InlayProperties;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ui.JBUI;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.AbstractBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

final class InlineNoteEditor implements Disposable {
    private static final Key<InlineNoteEditor> KEY = Key.create("inlinenotes.inlineDraft");
    private final Project project;
    private final Editor editor;
    private final NoteStore store;
    private final NoteStore.Comment comment;
    private final String path;
    private final VirtualFile file;
    private final RangeMarker anchor;
    private final String originalText;
    private final JTextArea text = new JTextArea(1, 40);
    private final JPanel panel;
    private final JLabel header;
    private final JLabel hints;
    private final JScrollPane body;
    private final JPanel footer = new JPanel(new BorderLayout(JBUI.scale(10), JBUI.scale(6)));
    private final JPanel buttons = new JPanel();
    private final JButton cancel = new JButton("Cancel");
    private final JButton save;
    private int footerMode = -1;
    private Inlay<ComponentInlayRenderer<JPanel>> inlay;
    private boolean closed;
    private boolean lineHidden;

    private InlineNoteEditor(Project project, Editor editor, NoteStore store,
                                NoteStore.Comment comment, RangeMarker anchor, String path, VirtualFile file) {
        this.project = project;
        this.editor = editor;
        this.store = store;
        this.comment = comment;
        this.path = path;
        this.file = file;
        this.anchor = anchor;
        originalText = comment == null ? "" : comment.text;
        lineHidden = comment == null && hidden(NoteGutters.install(editor, store, path));
        text.setText(originalText);
        text.setCaretPosition(originalText.length());
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        Font font = NoteInlays.cardFont();
        text.setFont(font);
        text.setBorder(JBUI.Borders.empty(8));
        panel = new JPanel(new BorderLayout(0, JBUI.scale(10))) {
            @Override
            protected void paintComponent(Graphics original) {
                super.paintComponent(original);
                Graphics2D graphics = (Graphics2D) original.create();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    NoteInlays.paintCardFrame(graphics, getWidth(), getHeight());
                } finally {
                    graphics.dispose();
                }
            }
        };
        panel.setOpaque(false);
        panel.setBorder(JBUI.Borders.empty(20));
        panel.setMinimumSize(new Dimension(0, 0));
        header = new JLabel(NoteInlays.dateHeader(comment));
        header.setFont(font.deriveFont(Font.BOLD));
        header.setForeground(NoteInlays.cardForeground());
        header.setPreferredSize(new Dimension(0, Math.max(JBUI.scale(24), header.getPreferredSize().height)));
        panel.add(header, BorderLayout.NORTH);
        body = new JScrollPane(text);
        body.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        body.setOpaque(false);
        body.setBorder(new AbstractBorder() {
            @Override
            public Insets getBorderInsets(Component component) {
                return JBUI.Borders.empty(2).getBorderInsets(component);
            }

            @Override
            public void paintBorder(Component component, Graphics original, int x, int y, int width, int height) {
                Graphics2D graphics = (Graphics2D) original.create();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    Color focus = UIManager.getColor("Component.focusColor");
                    graphics.setColor(text.isFocusOwner()
                            ? (focus == null ? text.getSelectionColor() : focus) : NoteInlays.cardBorder());
                    graphics.setStroke(new BasicStroke(JBUI.scale(text.isFocusOwner() ? 2 : 1)));
                    int inset = JBUI.scale(1);
                    graphics.drawRoundRect(x + inset, y + inset, width - 2 * inset - 1,
                            height - 2 * inset - 1, JBUI.scale(8), JBUI.scale(8));
                } finally {
                    graphics.dispose();
                }
            }
        });
        panel.add(body, BorderLayout.CENTER);
        text.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent event) {
                body.repaint();
            }

            @Override
            public void focusLost(FocusEvent event) {
                body.repaint();
            }
        });
        int shortcutMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        hints = new JLabel(((shortcutMask & InputEvent.META_DOWN_MASK) != 0 ? "⌘" : "Ctrl")
                + "+Enter to " + (comment == null ? "add" : "save") + " · Enter for newline");
        hints.setFont(font.deriveFont(Math.max(10f, font.getSize2D() - 1f)));
        Color muted = UIManager.getColor("Label.disabledForeground");
        hints.setForeground(muted == null ? NoteInlays.cardForeground() : muted);
        footer.setOpaque(false);
        buttons.setOpaque(false);
        save = new JButton(comment == null ? "Add comment" : "Save changes") {
            @Override
            protected void paintComponent(Graphics original) {
                Graphics2D graphics = (Graphics2D) original.create();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    Color primary = UIManager.getColor("Button.default.startBackground");
                    if (primary == null) primary = text.getSelectionColor();
                    graphics.setColor(getModel().isPressed() ? primary.darker() : primary);
                    graphics.fillRoundRect(0, 0, getWidth(), getHeight(), JBUI.scale(6), JBUI.scale(6));
                } finally {
                    graphics.dispose();
                }
                super.paintComponent(original);
            }
        };
        save.setContentAreaFilled(false);
        save.setOpaque(false);
        save.setBorderPainted(false);
        save.setBorder(JBUI.Borders.empty(6, 12));
        Color primaryText = UIManager.getColor("Button.default.foreground");
        save.setForeground(primaryText == null ? text.getSelectedTextColor() : primaryText);
        save.setDefaultCapable(false);
        cancel.setDefaultCapable(false);
        save.addActionListener(event -> finish(true));
        cancel.addActionListener(event -> finish(false));
        panel.add(footer, BorderLayout.SOUTH);
        resizeView();
        text.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                resizeView();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                resizeView();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                resizeView();
            }
        });
        Action saveAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                finish(true);
            }
        };
        Action cancelAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                finish(false);
            }
        };
        KeyStroke saveKey = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        for (JComponent target : new JComponent[]{text, panel}) {
            int condition = target == text ? JComponent.WHEN_FOCUSED : JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT;
            target.getInputMap(condition).put(saveKey, "saveInlineNote");
            target.getActionMap().put("saveInlineNote", saveAction);
            target.getInputMap(condition).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancelInlineNote");
            target.getActionMap().put("cancelInlineNote", cancelAction);
        }
    }

    static void resize(Editor editor) {
        InlineNoteEditor draft = editor.getUserData(KEY);
        if (draft != null && draft.usable()) draft.resizeView();
    }

    private void resizeView() {
        if (closed) return;
        int width = NoteInlays.cardWidth(editor, comment);
        int available = Math.max(1, width - JBUI.scale(40));
        int inputWidth = Math.max(1, available - JBUI.scale(36));
        FontMetrics metrics = text.getFontMetrics(text.getFont());
        int rows = 0;
        for (String line : text.getText().split("\n", -1)) {
            rows += Math.max(1, (int) Math.ceil((double) metrics.stringWidth(line) / inputWidth));
            if (rows >= 6) break;
        }
        rows = Math.max(1, Math.min(6, rows));
        if (text.getRows() != rows) text.setRows(rows);
        header.setText(NoteInlays.dateHeader(comment));
        layoutFooter(available);
        Dimension size = new Dimension(width, panel.getLayout().preferredLayoutSize(panel).height);
        panel.setPreferredSize(size);
        panel.setSize(size);
        panel.doLayout();
        panel.revalidate();
        panel.repaint();
        if (inlay != null && inlay.isValid()) inlay.update();
    }

    private void layoutFooter(int available) {
        int buttonWidth = cancel.getPreferredSize().width + save.getPreferredSize().width + JBUI.scale(8);
        int mode = buttonWidth > available ? 2
                : hints.getPreferredSize().width + buttonWidth + JBUI.scale(10) > available ? 1 : 0;
        if (mode == footerMode) return;
        footerMode = mode;
        footer.removeAll();
        buttons.removeAll();
        buttons.setLayout(new BoxLayout(buttons, mode == 2 ? BoxLayout.Y_AXIS : BoxLayout.X_AXIS));
        cancel.setAlignmentX(Component.RIGHT_ALIGNMENT);
        save.setAlignmentX(Component.RIGHT_ALIGNMENT);
        buttons.add(cancel);
        buttons.add(mode == 2 ? Box.createVerticalStrut(JBUI.scale(6)) : Box.createHorizontalStrut(JBUI.scale(8)));
        buttons.add(save);
        footer.add(hints, mode == 0 ? BorderLayout.WEST : BorderLayout.NORTH);
        footer.add(buttons, BorderLayout.EAST);
    }

    static void open(Project project, Editor editor, NoteStore store, NoteStore.Comment comment) {
        if (focusCurrent(editor)) return;
        if (store.isDisposed() || project.isDisposed() || editor.isDisposed()
                || !store.commentsFor(comment.path).contains(comment)) return;
        RangeMarker anchor = store.markerFor(comment, editor.getDocument());
        if (anchor == null || !anchor.isValid()) return;
        attach(new InlineNoteEditor(project, editor, store, comment, anchor, comment.path, null));
    }

    static void openAdd(Project project, Editor editor, NoteStore store, VirtualFile file, String path, int line) {
        if (focusCurrent(editor)) return;
        if (store.isDisposed() || project.isDisposed() || editor.isDisposed() || !file.isValid()
                || line < 0 || line >= editor.getDocument().getLineCount()) return;
        int offset = editor.getDocument().getLineEndOffset(line);
        RangeMarker anchor = editor.getDocument().createRangeMarker(offset, offset, true);
        anchor.setGreedyToLeft(true);
        InlineNoteEditor draft;
        try {
            draft = new InlineNoteEditor(project, editor, store, null, anchor, path, file);
        } catch (RuntimeException | Error failure) {
            anchor.dispose();
            throw failure;
        }
        attach(draft);
    }

    private static boolean focusCurrent(Editor editor) {
        InlineNoteEditor current = editor.getUserData(KEY);
        if (current != null) {
            if (current.usable()) {
                current.focus();
                return true;
            }
            current.close();
        }
        return false;
    }

    private static void attach(InlineNoteEditor draft) {
        draft.editor.putUserData(KEY, draft);
        try {
            draft.inlay = ComponentInlayKt.addComponentInlay(draft.editor, draft.anchor.getStartOffset(),
                    new InlayProperties().relatesToPrecedingText(true).showAbove(false),
                    draft.panel, null);
        } catch (RuntimeException | Error failure) {
            draft.close();
            throw failure;
        }
        if (draft.inlay == null || !draft.inlay.isValid()) {
            draft.close();
            draft.restore();
            return;
        }
        Disposer.register(draft.inlay, draft);
        NoteInlays.render(draft.editor, draft.store, draft.path);
        draft.focus();
    }

    static boolean isEditing(Editor editor, NoteStore.Comment comment) {
        InlineNoteEditor draft = editor.getUserData(KEY);
        return draft != null && !draft.closed && draft.comment != null && draft.comment == comment;
    }

    static void reconcile(Editor editor, NoteGutters gutters) {
        InlineNoteEditor draft = editor.getUserData(KEY);
        if (draft == null) return;
        if (!draft.targetAvailable() || (draft.inlay != null && !draft.inlay.isValid())) {
            draft.close();
            return;
        }
        boolean hidden = draft.hidden(gutters);
        if (hidden && (draft.comment != null || !draft.lineHidden)) draft.close();
        draft.lineHidden = hidden;
    }

    static void discard(Editor editor, NoteStore.Comment comment) {
        InlineNoteEditor draft = editor.getUserData(KEY);
        if (draft != null && draft.comment == comment) draft.close();
    }

    static void discard(Editor editor) {
        InlineNoteEditor draft = editor.getUserData(KEY);
        if (draft != null) draft.close();
    }

    private boolean targetAvailable() {
        return !closed && !project.isDisposed() && !editor.isDisposed() && !store.isDisposed()
                && anchor.isValid() && (comment == null
                ? file.isValid() && path.equals(NoteStore.relativePath(project, file))
                : store.commentsFor(path).contains(comment));
    }

    private boolean hidden(NoteGutters gutters) {
        if (comment != null) return gutters.isHidden(comment);
        int line = editor.getDocument().getLineNumber(anchor.getStartOffset());
        for (NoteStore.Comment existing : store.commentsFor(path)) {
            RangeMarker marker = store.markerFor(existing, editor.getDocument());
            if (marker != null && marker.isValid()
                    && editor.getDocument().getLineNumber(marker.getStartOffset()) == line
                    && gutters.isHidden(existing)) return true;
        }
        return false;
    }

    private boolean usable() {
        return targetAvailable() && inlay != null && inlay.isValid();
    }

    private void focus() {
        SwingUtilities.invokeLater(() -> {
            if (usable() && editor.getUserData(KEY) == this) text.requestFocusInWindow();
        });
    }

    private void finish(boolean save) {
        if (closed) return;
        if (!usable()) {
            close();
            return;
        }
        String value = text.getText();
        boolean changed = save && !value.isBlank() && !value.equals(originalText);
        int line = changed && comment == null ? editor.getDocument().getLineNumber(anchor.getStartOffset()) : -1;
        close();
        if (changed) {
            if (comment == null) {
                if (!project.isDisposed() && !editor.isDisposed() && !store.isDisposed() && file.isValid()) {
                    store.add(path, editor.getDocument(), line, value);
                    NoteInlays.show(project, file, line);
                }
            } else NoteInlays.commit(project, comment, value);
        }
        restore();
    }

    private void restore() {
        if (!project.isDisposed() && !editor.isDisposed() && !store.isDisposed()
                && (comment == null || (anchor.isValid() && store.commentsFor(path).contains(comment)))) {
            NoteInlays.render(editor, store, path);
        }
    }

    private void close() {
        if (closed) return;
        Inlay<?> current = inlay;
        releaseState();
        if (current != null) Disposer.dispose(current);
    }

    @Override
    public void dispose() {
        releaseState();
    }

    private void releaseState() {
        if (!closed && comment == null) anchor.dispose();
        closed = true;
        inlay = null;
        if (editor.getUserData(KEY) == this) editor.putUserData(KEY, null);
    }
}
