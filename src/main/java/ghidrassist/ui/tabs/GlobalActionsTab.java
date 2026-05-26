package ghidrassist.ui.tabs;

import ghidrassist.core.ActionConstants;
import ghidrassist.core.TabController;
import ghidrassist.services.GlobalAnalysisService.GlobalAnalysisConfig;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public class GlobalActionsTab extends JPanel {
    private static final long serialVersionUID = 1L;

    private final TabController controller;

    private JTable globalTable;
    private JCheckBox selectAllCheckBox;
    private JLabel progressLabel;

    private JButton analyzeAllButton;
    private JButton analyzeSelectedButton;
    private JButton stopButton;
    private JButton clearButton;
    private JButton applySelectedButton;

    private boolean hasSelection;
    private boolean running;

    private JPanel tuningPanel;
    private JSpinner spAutoApplyConfidence;
    private JSpinner spMaxCandidates;
    private JSpinner spMaxCodeChars;
    private JSpinner spMaxContextChars;
    private JSpinner spMaxStrings;
    private JSpinner spMaxNamedCallees;
    private JSpinner spLlmTimeoutSeconds;
    private JCheckBox cbRenameFunction;
    private JCheckBox cbRenameVariable;
    private JCheckBox cbRetypeVariable;
    private JCheckBox cbAutoCreateStruct;
    private JCheckBox cbSetSignature;

    public GlobalActionsTab(TabController controller) {
        super(new BorderLayout());
        this.controller = Objects.requireNonNull(controller, "controller must not be null");
        initializeComponents();
        layoutComponents();
        setupListeners();
    }

    private void initializeComponents() {
        selectAllCheckBox = new JCheckBox();
        progressLabel = new JLabel(" ");
        progressLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        globalTable = createGlobalTable();

        analyzeAllButton = new JButton("Analyze All");
        analyzeSelectedButton = new JButton("Analyze Selected");
        analyzeSelectedButton.setToolTipText(
            "Run global auto-analyze only on functions overlapping the current CodeBrowser selection.");
        analyzeSelectedButton.setEnabled(false);
        stopButton = new JButton("Stop");
        stopButton.setEnabled(false);
        clearButton = new JButton("Clear");
        applySelectedButton = new JButton("Apply Selected");

        spAutoApplyConfidence = new JSpinner(new SpinnerNumberModel(
            ActionConstants.AUTO_APPLY_CONFIDENCE, 0.0d, 1.0d, 0.05d));
        ((JSpinner.NumberEditor) spAutoApplyConfidence.getEditor()).getFormat().setMinimumFractionDigits(2);

        spMaxCandidates = new JSpinner(new SpinnerNumberModel(
            ActionConstants.GLOBAL_MAX_CANDIDATES, 0, 100000, 100));
        spMaxCodeChars = new JSpinner(new SpinnerNumberModel(
            ActionConstants.GLOBAL_MAX_CODE_CHARS, 1000, 20000, 500));
        spMaxContextChars = new JSpinner(new SpinnerNumberModel(
            ActionConstants.GLOBAL_MAX_CONTEXT_CHARS, 0, 10000, 500));
        spMaxStrings = new JSpinner(new SpinnerNumberModel(
            ActionConstants.GLOBAL_MAX_STRINGS, 0, 50, 1));
        spMaxNamedCallees = new JSpinner(new SpinnerNumberModel(
            ActionConstants.GLOBAL_MAX_NAMED_CALLEES, 0, 50, 1));
        spLlmTimeoutSeconds = new JSpinner(new SpinnerNumberModel(
            ActionConstants.GLOBAL_LLM_TIMEOUT_SECONDS, 10, 600, 10));

        cbRenameFunction = new JCheckBox("rename_function", true);
        cbRenameVariable = new JCheckBox("rename_variable (params)", true);
        cbRetypeVariable = new JCheckBox("retype_variable (params, conf=1.0)", true);
        cbAutoCreateStruct = new JCheckBox("auto_create_struct", true);
        cbSetSignature = new JCheckBox("set_signature (return+params, conf=1.0)", true);
    }

    private JTable createGlobalTable() {
        DefaultTableModel model = new DefaultTableModel(
            new Object[]{"Select", "Confidence", "Target", "Action", "Description", "Status", "Arguments"}, 0) {
            private static final long serialVersionUID = 1L;

            @Override
            public Class<?> getColumnClass(int column) {
                switch (column) {
                    case 0: return Boolean.class;
                    case 1: return Double.class;
                    default: return String.class;
                }
            }

            // Only the Select checkbox column is user-editable; Confidence/Target/Action/
            // Description/Status/Arguments are informational and must not be hand-edited,
            // since their values are downstream inputs to Apply / Navigate / context-menu logic.
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 0;
            }
        };
        JTable table = new JTable(model) {
            private static final long serialVersionUID = 1L;

            // Belt-and-suspenders defence against a Swing bug: dragging a column header
            // calls JTable.columnMoved -> editingStopped, which unconditionally writes
            // the editor value back to model[editingRow][editingColumn]. If the table
            // was cleared (rowCount=0) but a prior transient edit left editingRow set,
            // this throws ArrayIndexOutOfBoundsException. Refusing to ever enter edit
            // mode on text columns keeps editingRow/editingColumn pinned at -1.
            @Override
            public boolean editCellAt(int row, int column, java.util.EventObject e) {
                if (column != 0) return false;
                return super.editCellAt(row, column, e);
            }
        };
        table.getColumnModel().getColumn(0).setPreferredWidth(60);
        table.getColumnModel().getColumn(1).setPreferredWidth(80);
        table.getColumnModel().getColumn(2).setPreferredWidth(160);
        table.getColumnModel().getColumn(3).setPreferredWidth(130);
        table.getColumnModel().getColumn(4).setPreferredWidth(260);
        table.getColumnModel().getColumn(5).setPreferredWidth(90);
        table.getColumnModel().getColumn(6).setPreferredWidth(280);

        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model);
        sorter.setSortsOnUpdates(true);
        table.setRowSorter(sorter);
        sorter.setSortKeys(java.util.Collections.singletonList(
            new javax.swing.RowSorter.SortKey(1, javax.swing.SortOrder.DESCENDING)));

        setupSelectAllHeader(table);

        model.addTableModelListener(e -> {
            if (e.getColumn() == 0 || e.getColumn() == javax.swing.event.TableModelEvent.ALL_COLUMNS) {
                SwingUtilities.invokeLater(this::updateSelectAllCheckboxState);
            }
        });
        return table;
    }

    private void setupSelectAllHeader(JTable table) {
        TableCellRenderer headerRenderer = (t, value, isSelected, hasFocus, row, column) -> {
            if (column == 0) {
                selectAllCheckBox.setText("Select");
                selectAllCheckBox.setHorizontalAlignment(SwingConstants.CENTER);
                return selectAllCheckBox;
            }
            return t.getTableHeader().getDefaultRenderer()
                .getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
        };
        table.getColumnModel().getColumn(0).setHeaderRenderer(headerRenderer);
        table.getTableHeader().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                JTableHeader header = (JTableHeader) e.getSource();
                int columnIndex = header.columnAtPoint(e.getPoint());
                if (columnIndex == 0) {
                    boolean currentState = selectAllCheckBox.isSelected();
                    selectAllCheckBox.setSelected(!currentState);
                    toggleAllRowSelections(!currentState);
                    header.repaint();
                }
            }
        });
    }

    private void toggleAllRowSelections(boolean selectAll) {
        DefaultTableModel model = (DefaultTableModel) globalTable.getModel();
        for (int row = 0; row < model.getRowCount(); row++) {
            model.setValueAt(selectAll, row, 0);
        }
        globalTable.repaint();
    }

    private void updateSelectAllCheckboxState() {
        DefaultTableModel model = (DefaultTableModel) globalTable.getModel();
        if (model.getRowCount() == 0) {
            selectAllCheckBox.setSelected(false);
            return;
        }
        boolean allSelected = true;
        for (int row = 0; row < model.getRowCount(); row++) {
            Boolean value = (Boolean) model.getValueAt(row, 0);
            if (value == null || !value) { allSelected = false; break; }
        }
        selectAllCheckBox.setSelected(allSelected);
        globalTable.getTableHeader().repaint();
    }

    private void layoutComponents() {
        tuningPanel = buildTuningPanel();

        JPanel north = new JPanel(new BorderLayout());
        north.add(progressLabel, BorderLayout.NORTH);
        north.add(tuningPanel, BorderLayout.CENTER);
        add(north, BorderLayout.NORTH);

        add(new JScrollPane(globalTable), BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel();
        buttonPanel.add(analyzeAllButton);
        buttonPanel.add(analyzeSelectedButton);
        buttonPanel.add(stopButton);
        buttonPanel.add(clearButton);
        buttonPanel.add(applySelectedButton);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    private JPanel buildTuningPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Tuning"));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(2, 4, 2, 4);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        addSpinnerRow(panel, g, row++, "Auto-apply confidence:", spAutoApplyConfidence);
        addSpinnerRow(panel, g, row++, "Max candidates (0=all):", spMaxCandidates);
        addSpinnerRow(panel, g, row++, "Max code chars:", spMaxCodeChars);
        addSpinnerRow(panel, g, row++, "Max context chars:", spMaxContextChars);
        addSpinnerRow(panel, g, row++, "Max strings:", spMaxStrings);
        addSpinnerRow(panel, g, row++, "Max named callees:", spMaxNamedCallees);
        addSpinnerRow(panel, g, row++, "LLM timeout (s):", spLlmTimeoutSeconds);

        g.gridx = 0; g.gridy = row; g.weightx = 0;
        panel.add(new JLabel("Retype confidence floor:"), g);
        g.gridx = 1; g.weightx = 1;
        panel.add(new JLabel(String.format("%.2f (locked)", ActionConstants.RETYPE_CONFIDENCE_FLOOR)), g);
        row++;

        g.gridx = 0; g.gridy = row; g.gridwidth = 2; g.weightx = 1;
        JPanel actionsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        actionsRow.add(new JLabel("Actions:"));
        actionsRow.add(cbRenameFunction);
        actionsRow.add(cbRenameVariable);
        actionsRow.add(cbRetypeVariable);
        actionsRow.add(cbAutoCreateStruct);
        actionsRow.add(cbSetSignature);
        panel.add(actionsRow, g);
        g.gridwidth = 1;

        return panel;
    }

    private static void addSpinnerRow(JPanel panel, GridBagConstraints g, int row, String label, JSpinner spinner) {
        g.gridx = 0; g.gridy = row; g.weightx = 0;
        panel.add(new JLabel(label), g);
        g.gridx = 1; g.weightx = 1;
        panel.add(spinner, g);
    }

    private void setupListeners() {
        analyzeAllButton.addActionListener(e -> controller.handleAutoAnalyzeBinary());
        analyzeSelectedButton.addActionListener(e -> controller.handleAutoAnalyzeSelected());
        stopButton.addActionListener(e -> controller.handleStopGlobalAnalyze());
        clearButton.addActionListener(e -> {
            ((DefaultTableModel) globalTable.getModel()).setRowCount(0);
            updateSelectAllCheckboxState();
        });
        applySelectedButton.addActionListener(e -> controller.handleApplyGlobalActions(globalTable));

        globalTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
                    int viewRow = globalTable.rowAtPoint(e.getPoint());
                    int viewCol = globalTable.columnAtPoint(e.getPoint());
                    if (viewRow < 0 || viewCol != 2) return;
                    controller.handleGlobalActionsNavigate(globalTable.convertRowIndexToModel(viewRow));
                }
            }
            @Override public void mousePressed(MouseEvent e)  { maybePopup(e); }
            @Override public void mouseReleased(MouseEvent e) { maybePopup(e); }

            // Popup triggers are platform-specific (pressed on macOS, released on Windows/Linux),
            // so both events must be handled. SwingUtilities.isRightMouseButton is kept as a
            // belt-and-braces fallback for platforms/LAFs that do not set the popup flag reliably.
            private void maybePopup(MouseEvent e) {
                if (!(e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e))) return;
                int viewRow = globalTable.rowAtPoint(e.getPoint());
                if (viewRow < 0) return;
                if (!globalTable.isRowSelected(viewRow)) {
                    globalTable.setRowSelectionInterval(viewRow, viewRow);
                }
                int modelRow = globalTable.convertRowIndexToModel(viewRow);
                showRowPopup(e.getComponent(), e.getX(), e.getY(), modelRow);
            }
        });
    }

    private void showRowPopup(Component invoker, int x, int y, int modelRow) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem apply  = new JMenuItem("Apply");
        JMenuItem remove = new JMenuItem("Remove");
        JMenuItem goTo   = new JMenuItem("Go to Target");
        apply.addActionListener(ev  -> controller.handleGlobalActionsApplyRow(modelRow));
        remove.addActionListener(ev -> controller.handleGlobalActionsRemoveRow(modelRow));
        goTo.addActionListener(ev   -> controller.handleGlobalActionsNavigate(modelRow));
        menu.add(apply);
        menu.add(goTo);
        menu.addSeparator();
        menu.add(remove);
        menu.show(invoker, x, y);
    }

    public DefaultTableModel getTableModel() {
        return (DefaultTableModel) globalTable.getModel();
    }

    public JTable getTable() {
        return globalTable;
    }

    public void setProgressText(String text) {
        String safe = (text == null) ? " " : escapeForLabel(text);
        SwingUtilities.invokeLater(() -> progressLabel.setText(safe));
    }

    // JLabel treats any string starting with "<html>" as HTML. Neutralize by stripping
    // angle brackets and ampersands from untrusted LLM-derived text before display.
    private static String escapeForLabel(String s) {
        if (s == null || s.isEmpty()) return " ";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Toggle run state. When running, tuning controls and non-stop buttons are disabled so the
     * user cannot mutate configuration mid-run.
     */
    public void setRunning(boolean running) {
        this.running = running;
        SwingUtilities.invokeLater(() -> {
            analyzeAllButton.setEnabled(!running);
            analyzeSelectedButton.setEnabled(!running && hasSelection);
            stopButton.setEnabled(running);
            clearButton.setEnabled(!running);
            applySelectedButton.setEnabled(!running);
            setEnabledRecursive(tuningPanel, !running);
        });
    }

    /**
     * Called by TabController when the CodeBrowser ProgramSelection changes. The Analyze Selected
     * button is only meaningful while a non-empty selection exists and no run is in progress.
     */
    public void onSelectionChanged(boolean hasSelection) {
        this.hasSelection = hasSelection;
        SwingUtilities.invokeLater(() -> analyzeSelectedButton.setEnabled(!running && hasSelection));
    }

    private static void setEnabledRecursive(Component root, boolean enabled) {
        root.setEnabled(enabled);
        if (root instanceof Container) {
            for (Component child : ((Container) root).getComponents()) {
                setEnabledRecursive(child, enabled);
            }
        }
    }

    /**
     * Snapshot current tuning values into a fresh {@link GlobalAnalysisConfig}. Invoked by
     * TabController at the start of each Analyze All run.
     */
    public GlobalAnalysisConfig buildConfig() {
        GlobalAnalysisConfig c = GlobalAnalysisConfig.defaults();
        c.autoApplyConfidence = ((Number) spAutoApplyConfidence.getValue()).doubleValue();
        c.maxCandidates = ((Number) spMaxCandidates.getValue()).intValue();
        c.maxCodeChars = ((Number) spMaxCodeChars.getValue()).intValue();
        c.maxContextChars = ((Number) spMaxContextChars.getValue()).intValue();
        c.maxStrings = ((Number) spMaxStrings.getValue()).intValue();
        c.maxNamedCallees = ((Number) spMaxNamedCallees.getValue()).intValue();
        c.llmTimeoutSeconds = ((Number) spLlmTimeoutSeconds.getValue()).longValue();

        Set<String> acts = new LinkedHashSet<>();
        if (cbRenameFunction.isSelected()) acts.add("rename_function");
        if (cbRenameVariable.isSelected()) acts.add("rename_variable");
        if (cbRetypeVariable.isSelected()) acts.add("retype_variable");
        if (cbAutoCreateStruct.isSelected()) acts.add("auto_create_struct");
        if (cbSetSignature.isSelected()) acts.add("set_signature");
        c.actions = acts;
        return c;
    }
}
