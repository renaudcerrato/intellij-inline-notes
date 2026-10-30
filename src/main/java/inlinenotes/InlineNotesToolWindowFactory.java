package inlinenotes;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;

import javax.swing.Icon;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class InlineNotesToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override
    public void createToolWindowContent(Project project, ToolWindow toolWindow) {
        CommentView view = new CommentView(project, toolWindow);
        Content content = toolWindow.getContentManager().getFactory().createContent(view.component, "", false);
        content.setDisposer(view);
        content.setPreferredFocusableComponent(view.tree);
        toolWindow.getContentManager().addContent(content);
        toolWindow.setTitleActions(List.of(view.copy, view.clear));
    }

    private static final class CommentView implements Disposable {
        private final Project project;
        private final ToolWindow toolWindow;
        private final JPanel component = new JPanel(new BorderLayout());
        private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("Inline Notes");
        private final DefaultTreeModel model = new DefaultTreeModel(root);
        private final CommentTree tree = new CommentTree(model);
        private final CopyCommentsAction copy = new CopyCommentsAction();
        private final ClearAllCommentsAction clear = new ClearAllCommentsAction();
        private final Disposable subscription;
        private volatile boolean disposed;

        CommentView(Project project, ToolWindow toolWindow) {
            this.project = project;
            this.toolWindow = toolWindow;
            tree.setRootVisible(false);
            tree.setShowsRootHandles(true);
            tree.setRowHeight(0);
            tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
            tree.setCellRenderer(new CommentListRenderer(tree));
            JScrollPane scroll = new JScrollPane(tree);
            scroll.setBorder(JBUI.Borders.empty());
            scroll.getViewport().addChangeListener(event -> tree.repaint());
            component.add(scroll, BorderLayout.CENTER);
            tree.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    if (!SwingUtilities.isLeftMouseButton(event) || event.getClickCount() != 2) return;
                    TreePath path = tree.getClosestPathForLocation(event.getX(), event.getY());
                    Rectangle bounds = path == null ? null : tree.getPathBounds(path);
                    if (bounds != null && event.getY() >= bounds.y && event.getY() < bounds.y + bounds.height
                            && ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject()
                            instanceof Branch branch && branch.file()) {
                        tree.setSelectionPath(path);
                        openSelected();
                    }
                }
            });
            subscription = project.getService(NoteStore.class).addListener(this::refreshOnEdt);
            refresh();
        }

        private void refreshOnEdt() {
            if (SwingUtilities.isEventDispatchThread()) refresh();
            else SwingUtilities.invokeLater(this::refresh);
        }

        private void refresh() {
            if (disposed || project.isDisposed()) return;
            Branch selected = selectedBranch();
            String selectedFile = selected != null && selected.file() ? selected.path() : null;
            Set<String> expanded = new HashSet<>();
            var paths = tree.getExpandedDescendants(new TreePath(root.getPath()));
            if (paths != null) {
                while (paths.hasMoreElements()) {
                    Object value = ((DefaultMutableTreeNode) paths.nextElement().getLastPathComponent()).getUserObject();
                    if (value instanceof Branch branch) expanded.addAll(branch.folderPaths());
                }
            }
            List<NoteStore.Comment> comments = project.getService(NoteStore.class).allComments();
            Map<String, List<NoteStore.Comment>> files = new LinkedHashMap<>();
            for (NoteStore.Comment comment : comments) {
                files.computeIfAbsent(comment.path, path -> new ArrayList<>()).add(comment);
            }
            int largestCount = files.values().stream().mapToInt(List::size).max().orElse(0);
            tree.columnWidth = tree.getFontMetrics(tree.getFont()).stringWidth(Integer.toString(largestCount))
                    + JBUI.scale(30);
            DefaultMutableTreeNode rawRoot = new DefaultMutableTreeNode();
            Map<String, DefaultMutableTreeNode> branches = new LinkedHashMap<>();
            for (var file : files.entrySet()) addFile(rawRoot, file.getKey(), file.getValue().size(), branches);
            root.removeAllChildren();
            for (int i = 0; i < rawRoot.getChildCount(); i++) {
                root.add(compact((DefaultMutableTreeNode) rawRoot.getChildAt(i)));
            }
            model.reload();
            tree.expandPath(new TreePath(root.getPath()));
            restoreExpansion(root, expanded);
            var nodes = root.depthFirstEnumeration();
            while (nodes.hasMoreElements()) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
                if (node.getUserObject() instanceof Branch branch && branch.file()
                        && branch.path().equals(selectedFile)) {
                    tree.setSelectionPath(new TreePath(node.getPath()));
                    break;
                }
            }
            copy.getTemplatePresentation().setEnabled(!comments.isEmpty());
        }

        private void addFile(DefaultMutableTreeNode rawRoot, String filePath, int commentCount,
                             Map<String, DefaultMutableTreeNode> branches) {
            String[] parts = filePath.split("/");
            String path = "";
            DefaultMutableTreeNode parent = rawRoot;
            for (int i = 0; i < parts.length; i++) {
                path = path.isEmpty() ? parts[i] : path + "/" + parts[i];
                DefaultMutableTreeNode node = branches.get(path);
                if (node == null) {
                    boolean file = i == parts.length - 1;
                    VirtualFile virtualFile = file ? ReviewClipboard.findFile(project, path) : null;
                    Icon icon = file ? (virtualFile == null ? AllIcons.Nodes.Unknown : virtualFile.getFileType().getIcon())
                            : AllIcons.Nodes.Folder;
                    if (icon == null) icon = AllIcons.Nodes.Unknown;
                    node = new DefaultMutableTreeNode(new Branch(path, parts[i], file, icon,
                            file ? List.of() : List.of(path), file ? 1 : 0, file ? commentCount : 0), !file);
                    parent.add(node);
                    branches.put(path, node);
                }
                parent = node;
            }
        }

        private DefaultMutableTreeNode compact(DefaultMutableTreeNode raw) {
            Branch branch = (Branch) raw.getUserObject();
            if (branch.file()) return new DefaultMutableTreeNode(branch, false);
            String name = branch.name();
            List<String> paths = new ArrayList<>(branch.folderPaths());
            while (raw.getChildCount() == 1) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) raw.getChildAt(0);
                Branch next = (Branch) child.getUserObject();
                if (next.file()) break;
                raw = child;
                branch = next;
                name += "/" + next.name();
                paths.addAll(next.folderPaths());
            }
            DefaultMutableTreeNode node = new DefaultMutableTreeNode();
            int fileCount = 0;
            for (int i = 0; i < raw.getChildCount(); i++) {
                DefaultMutableTreeNode child = compact((DefaultMutableTreeNode) raw.getChildAt(i));
                node.add(child);
                fileCount += ((Branch) child.getUserObject()).fileCount();
            }
            node.setUserObject(new Branch(branch.path(), name, false, branch.icon(),
                    List.copyOf(paths), fileCount, 0));
            return node;
        }

        private void restoreExpansion(DefaultMutableTreeNode parent, Set<String> expanded) {
            for (int i = 0; i < parent.getChildCount(); i++) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) parent.getChildAt(i);
                Branch branch = (Branch) node.getUserObject();
                if (!branch.file() && branch.folderPaths().stream().anyMatch(expanded::contains)) {
                    tree.expandPath(new TreePath(node.getPath()));
                    restoreExpansion(node, expanded);
                }
            }
        }

        private Branch selectedBranch() {
            TreePath path = tree.getSelectionPath();
            if (path == null) return null;
            Object value = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
            return value instanceof Branch branch ? branch : null;
        }

        private void openSelected() {
            Branch selected = selectedBranch();
            if (selected == null || !selected.file() || project.isDisposed()) return;
            VirtualFile file = ReviewClipboard.findFile(project, selected.path);
            if (file == null) {
                Messages.showInfoMessage(project, "File is unavailable: " + selected.path, "Inline Notes");
                return;
            }
            NoteStore store = project.getService(NoteStore.class);
            NoteStore.Comment top = store.commentsFor(selected.path()).stream()
                    .min(Comparator.comparingInt(store::currentLine)).orElse(null);
            if (top == null) return;
            int line = store.currentLine(top);
            FileEditorManager manager = FileEditorManager.getInstance(project);
            Editor editor = manager.openTextEditor(new OpenFileDescriptor(project, file, line, 0), true);
            if (editor != null) manager.runWhenLoaded(editor, () -> {
                if (!disposed && !store.isDisposed() && !project.isDisposed() && !editor.isDisposed()
                        && store.commentsFor(selected.path()).contains(top)) {
                    NoteInlays.reveal(editor, store, selected.path(), store.currentLine(top));
                }
            });
        }

        @Override
        public void dispose() {
            disposed = true;
            subscription.dispose();
            if (!toolWindow.isDisposed()) toolWindow.setTitleActions(List.of());
        }
    }

    private record Branch(String path, String name, boolean file, Icon icon, List<String> folderPaths,
                          int fileCount, int commentCount) {}

    private static final class CopyCommentsAction extends DumbAwareAction {
        CopyCommentsAction() {
            super("Copy comments", "Copy comments", AllIcons.Actions.Copy);
        }

        @Override
        public ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(AnActionEvent event) {
            Project project = event.getProject();
            event.getPresentation().setEnabled(project != null && !project.isDisposed()
                    && !project.getService(NoteStore.class).allComments().isEmpty());
        }

        @Override
        public void actionPerformed(AnActionEvent event) {
            Project project = event.getProject();
            if (project != null && !project.isDisposed()
                    && !project.getService(NoteStore.class).allComments().isEmpty()) ReviewClipboard.copy(project);
        }
    }

    private static final class ClearAllCommentsAction extends DumbAwareAction {
        ClearAllCommentsAction() {
            super("Clear all comments", "Clear all comments", AllIcons.General.Delete);
        }

        @Override
        public ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(AnActionEvent event) {
            Project project = event.getProject();
            event.getPresentation().setEnabled(project != null && !project.isDisposed()
                    && !project.getService(NoteStore.class).allComments().isEmpty());
        }

        @Override
        public void actionPerformed(AnActionEvent event) {
            Project project = event.getProject();
            if (project == null || project.isDisposed()) return;
            NoteStore store = project.getService(NoteStore.class);
            if (store.allComments().isEmpty()) return;
            int answer = Messages.showYesNoDialog(project,
                    "Clear all comments in this project? This cannot be undone.",
                    "Clear all comments", Messages.getWarningIcon());
            if (answer == Messages.YES && !store.isDisposed() && !project.isDisposed()) {
                NoteInlays.clearAll(project);
            }
        }
    }

    private static final class CommentListRenderer extends ColoredTreeCellRenderer {
        private final CommentTree tree;
        private int row;

        CommentListRenderer(CommentTree tree) {
            this.tree = tree;
        }

        @Override
        public void customizeCellRenderer(JTree tree, Object value, boolean selected,
                                          boolean expanded, boolean leaf, int row, boolean focused) {
            this.row = row;
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
            if (node.getUserObject() instanceof Branch branch) {
                setIcon(branch.icon());
                append(branch.name(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                if (!branch.file()) append("  " + branch.fileCount() + (branch.fileCount() == 1 ? " file" : " files"),
                        SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            size.width += tree.columnWidth + JBUI.scale(8);
            size.height = Math.max(JBUI.scale(24), getFontMetrics(getFont()).getHeight() + JBUI.scale(4));
            return size;
        }

        @Override
        protected void paintComponent(Graphics original) {
            Rectangle bounds = tree.getRowBounds(row);
            Rectangle visible = tree.getVisibleRect();
            Graphics graphics = original.create();
            try {
                int right = visible.x + visible.width - tree.columnWidth - JBUI.scale(8);
                graphics.clipRect(0, 0, bounds == null ? getWidth() : Math.max(0, right - bounds.x), getHeight());
                super.paintComponent(graphics);
            } finally {
                graphics.dispose();
            }
        }
    }

    private static final class CommentTree extends Tree {
        private int columnWidth = JBUI.scale(40);

        CommentTree(DefaultTreeModel model) {
            super(model);
        }

        @Override
        protected void paintComponent(Graphics original) {
            super.paintComponent(original);
            Graphics2D graphics = (Graphics2D) original.create();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                graphics.setStroke(new BasicStroke(JBUI.scale(1)));
                graphics.setFont(getFont());
                Rectangle visible = getVisibleRect();
                FontMetrics metrics = graphics.getFontMetrics();
                int right = visible.x + visible.width - JBUI.scale(10);
                for (int row = getClosestRowForLocation(visible.x, visible.y); row >= 0 && row < getRowCount(); row++) {
                    Rectangle bounds = getRowBounds(row);
                    if (bounds == null || bounds.y >= visible.y + visible.height) break;
                    Object value = ((DefaultMutableTreeNode) getPathForRow(row).getLastPathComponent()).getUserObject();
                    if (!(value instanceof Branch branch) || !branch.file()) continue;
                    Color color = UIManager.getColor(isRowSelected(row) && isFocusOwner()
                            ? "Tree.selectionForeground" : "Label.disabledForeground");
                    graphics.setColor(color == null ? getForeground() : color);
                    String count = Integer.toString(branch.commentCount());
                    int x = right - metrics.stringWidth(count);
                    graphics.drawString(count, x, bounds.y + (bounds.height - metrics.getHeight()) / 2 + metrics.getAscent());
                    paintBubble(graphics, x - JBUI.scale(18), bounds.y + (bounds.height - JBUI.scale(12)) / 2);
                }
            } finally {
                graphics.dispose();
            }
        }

        private void paintBubble(Graphics2D graphics, int x, int y) {
            int width = JBUI.scale(12);
            int height = JBUI.scale(9);
            Path2D bubble = new Path2D.Double();
            bubble.moveTo(x, y);
            bubble.lineTo(x + width, y);
            bubble.lineTo(x + width, y + height);
            bubble.lineTo(x + JBUI.scale(5), y + height);
            bubble.lineTo(x + JBUI.scale(2), y + height + JBUI.scale(3));
            bubble.lineTo(x + JBUI.scale(2), y + height);
            bubble.lineTo(x, y + height);
            bubble.closePath();
            graphics.draw(bubble);
        }
    }
}
