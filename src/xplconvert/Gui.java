package xplconvert;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.TransferHandler.TransferSupport;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * The application window: pick presets, pick a direction, convert.  All conversion work runs
 * on a background thread and streams into the log, so the window never freezes.
 */
public final class Gui {
    private final DefaultListModel<String> model = new DefaultListModel<>();
    private final JList<String> list = new JList<>(model);

    private final JRadioButton sdkRadio =
            new JRadioButton("SDK layout  —  VST2 Plugins/<Folder>/VST/<name>-arm64.so, MPCP state", true);
    private final JRadioButton origRadio =
            new JRadioButton("Classic layout  —  Synths/<Folder>/<name>.so, text state");
    private final JComboBox<String> archBox = new JComboBox<>(Options.ARCHES);

    private final JTextField installDir = new JTextField();
    /** One parent field for both layouts; targetChanged() fills it in per layout. */
    private final JTextField parentFolder = new JTextField(Options.SDK_PARENT);
    private final JTextField extraKeys = new JTextField();
    private final JTextField outDir = new JTextField();
    private final JTextField suffix = new JTextField("-sdk");
    private final JCheckBox archSuffixBox =
            new JCheckBox("Add arch suffix to the SDK binary name  (-arm64 / -arm32)", true);
    private final JCheckBox dryRun = new JCheckBox("Dry run — report only, write nothing");

    private final JButton convertBtn = new JButton("Convert");
    private final JLabel status = new JLabel(" ");
    private final JTextArea log = new JTextArea(14, 92);

    private final JPanel root = new JPanel();

    public Gui() {
        buildPanel();
    }

    /** The whole window contents, without the frame (so it can be built headless for tests). */
    public JComponent panel() {
        return root;
    }

    private void buildPanel() {
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        root.add(stretch(presetsPanel()));
        root.add(stretch(directionPanel()));
        root.add(stretch(optionsPanel()));
        root.add(stretch(actionPanel()));
        root.add(stretch(logPanel()));

        ButtonGroup group = new ButtonGroup();
        group.add(sdkRadio);
        group.add(origRadio);
        sdkRadio.addActionListener(e -> targetChanged());
        origRadio.addActionListener(e -> targetChanged());
        archSuffixBox.addActionListener(e -> targetChanged());
        targetChanged();
    }

    private static JComponent stretch(JComponent c) {
        c.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        return c;
    }

    private static JPanel titled(String title, JComponent inner) {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBorder(BorderFactory.createTitledBorder(title));
        p.add(inner, BorderLayout.CENTER);
        return p;
    }

    // ---------------------------------------------------------------- presets list
    private JPanel presetsPanel() {
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(760, 96));

        JButton addFiles = new JButton("Add files…");
        JButton addFolder = new JButton("Add folder…");
        JButton remove = new JButton("Remove");
        JButton clear = new JButton("Clear");
        addFiles.addActionListener(e -> chooseFiles());
        addFolder.addActionListener(e -> chooseFolder());
        remove.addActionListener(e -> {
            int[] sel = list.getSelectedIndices();
            for (int i = sel.length - 1; i >= 0; i--) {
                model.remove(sel[i]);
            }
        });
        clear.addActionListener(e -> model.clear());

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(addFiles);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(addFolder);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(remove);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(clear);
        buttons.add(Box.createHorizontalGlue());

        JPanel inner = new JPanel(new BorderLayout(0, 6));
        inner.add(scroll, BorderLayout.CENTER);
        inner.add(buttons, BorderLayout.SOUTH);
        return titled("Presets  (.xpl files or folders — you can also drag and drop them here)", inner);
    }

    private void chooseFiles() {
        JFileChooser fc = new JFileChooser();
        fc.setMultiSelectionEnabled(true);
        fc.setFileFilter(new FileNameExtensionFilter("MPC presets (*.xpl)", "xpl"));
        if (fc.showOpenDialog(root) == JFileChooser.APPROVE_OPTION) {
            for (File f : fc.getSelectedFiles()) {
                addPath(f.getAbsolutePath());
            }
        }
    }

    private void chooseFolder() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(root) == JFileChooser.APPROVE_OPTION) {
            addPath(fc.getSelectedFile().getAbsolutePath());
        }
    }

    private void addPath(String p) {
        if (!model.contains(p)) {
            model.addElement(p);
        }
    }

    private void enableDragAndDrop() {
        list.setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) {
                    return false;
                }
                try {
                    Transferable t = support.getTransferable();
                    for (File f : (List<File>) t.getTransferData(DataFlavor.javaFileListFlavor)) {
                        if (f.isDirectory() || f.getName().toLowerCase().endsWith(".xpl")) {
                            addPath(f.getAbsolutePath());
                        }
                    }
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        });
        try {
            list.setDragEnabled(true);
        } catch (java.awt.HeadlessException e) {
            // no mouse in this session; dropping still works when a display is present
        }
    }

    // ---------------------------------------------------------------- direction
    private JPanel directionPanel() {
        JPanel radios = new JPanel();
        radios.setLayout(new BoxLayout(radios, BoxLayout.Y_AXIS));
        sdkRadio.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        origRadio.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        radios.add(sdkRadio);
        radios.add(origRadio);

        archBox.setMaximumSize(new Dimension(120, archBox.getPreferredSize().height));
        JLabel archLabel = new JLabel("SDK binary:");
        JPanel archRow = new JPanel();
        archRow.setLayout(new BoxLayout(archRow, BoxLayout.X_AXIS));
        archRow.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        archRow.add(archLabel);
        archRow.add(Box.createHorizontalStrut(6));
        archRow.add(archBox);
        archRow.add(Box.createHorizontalGlue());

        JPanel inner = new JPanel(new BorderLayout(0, 4));
        inner.add(radios, BorderLayout.CENTER);
        inner.add(archRow, BorderLayout.SOUTH);
        return titled("Convert to", inner);
    }

    private void targetChanged() {
        String want = sdkRadio.isSelected() ? "-sdk" : "-orig";
        String cur = suffix.getText().trim();
        if (cur.isEmpty() || "-sdk".equals(cur) || "-orig".equals(cur)) {
            suffix.setText(want);
        }
        archBox.setEnabled(sdkRadio.isSelected() && archSuffixBox.isSelected());
        // the parent folder is a property of the layout: fill it in on every switch
        parentFolder.setText(sdkRadio.isSelected() ? Options.SDK_PARENT : Options.ORIG_PARENT);
    }

    // ---------------------------------------------------------------- options
    private JPanel optionsPanel() {
        JButton installBrowse = browseButton(installDir);
        JButton outBrowse = browseButton(outDir);

        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 6, 3, 6);
        c.anchor = GridBagConstraints.WEST;
        int row = 0;
        addRow(p, c, row++, "Install folder on device (optional):", withBrowse(installDir, installBrowse));
        addRow(p, c, row++, "Parent folder:", parentFolder);
        addRow(p, c, row++, "Extra-state keys (comma-separated):", extraKeys);
        addRow(p, c, row++, "Output folder (blank = beside each source):", withBrowse(outDir, outBrowse));
        addRow(p, c, row++, "Filename suffix (when no output folder):", suffix);
        c.gridx = 1;
        c.gridy = row;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        p.add(archSuffixBox, c);
        c.gridy = ++row;
        p.add(dryRun, c);

        JLabel hint = new JLabel("<html>The parent folder fills itself in per layout — VST2 Plugins for the SDK, "
                + "Synths for classic.<br/>The install folder says where the plugin lives on the device: "
                + "/media/&lt;drive&gt;, …/VST2 Plugins, or the full plugin folder —<br/>whatever is missing "
                + "(parent, plugin folder) is filled in, and extra levels such as VST/ are kept as typed. "
                + "Leave it blank to derive from the preset's own path.<br/>Extra-state keys name values that "
                + "belong in the SDK's extra state rather than its parameter list (loaded file names, for "
                + "example).</html>");
        GridBagConstraints hc = new GridBagConstraints();
        hc.gridx = 0;
        hc.gridy = row + 1;
        hc.gridwidth = 2;
        hc.anchor = GridBagConstraints.WEST;
        hc.insets = new Insets(6, 6, 3, 6);
        p.add(hint, hc);

        return titled("Options", p);
    }

    private static void addRow(JPanel p, GridBagConstraints c, int row, String label, JComponent field) {
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0;
        c.gridwidth = 1;
        c.fill = GridBagConstraints.NONE;
        p.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        p.add(field, c);
    }

    private JButton browseButton(JTextField target) {
        JButton b = new JButton("Browse…");
        b.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (target == outDir && !target.getText().trim().isEmpty()) {
                fc.setCurrentDirectory(new File(target.getText().trim()));
            }
            if (fc.showOpenDialog(root) == JFileChooser.APPROVE_OPTION) {
                target.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });
        return b;
    }

    private static JComponent withBrowse(JTextField tf, JButton b) {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        p.add(tf, BorderLayout.CENTER);
        p.add(b, BorderLayout.EAST);
        return p;
    }

    // ---------------------------------------------------------------- actions + log
    private JPanel actionPanel() {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        convertBtn.addActionListener(e -> convert());
        p.add(convertBtn, BorderLayout.WEST);
        p.add(status, BorderLayout.CENTER);
        return p;
    }

    private JPanel logPanel() {
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        log.setLineWrap(false);
        JScrollPane scroll = new JScrollPane(log);
        scroll.setPreferredSize(new Dimension(760, 260));
        return titled("Report", scroll);
    }

    /** Reads the form into an Options object — the exact settings the Convert button uses. */
    Options buildOptions(List<String> paths) {
        Options o = new Options();
        o.target = sdkRadio.isSelected() ? "sdk" : "original";
        o.arch = (String) archBox.getSelectedItem();
        o.archSuffix = archSuffixBox.isSelected();
        o.sdkParent = o.origParent = parentFolder.getText().trim();
        String id = installDir.getText().trim();
        o.installDir = id.isEmpty() ? null : id;
        for (String k : extraKeys.getText().split(",")) {
            if (!k.trim().isEmpty()) {
                o.extraKeys.add(k.trim());
            }
        }
        String od = outDir.getText().trim();
        o.out = od.isEmpty() ? null : od;
        String sx = suffix.getText().trim();
        o.suffix = sx.isEmpty() ? null : sx;
        o.dryRun = dryRun.isSelected();
        o.paths = paths;
        return o;
    }

    private void convert() {
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < model.size(); i++) {
            paths.add(model.get(i));
        }
        if (paths.isEmpty()) {
            status.setText("Add some .xpl presets first.");
            return;
        }
        Options o = buildOptions(paths);
        convertBtn.setEnabled(false);
        status.setText("Converting…");
        log.setText("");
        Thread worker = new Thread(() -> {
            int rc = Engine.run(o, this::append);
            SwingUtilities.invokeLater(() -> {
                convertBtn.setEnabled(true);
                status.setText(rc == 0
                        ? String.format("%d preset(s) converted.", paths.size())
                        : "Finished with errors — see the report.");
            });
        }, "xpl-convert");
        worker.setDaemon(true);
        worker.start();
    }

    private void append(String line) {
        SwingUtilities.invokeLater(() -> {
            log.append(line + "\n");
            log.setCaretPosition(log.getDocument().getLength());
        });
    }

    /** Shows the window. */
    public void show() {
        enableDragAndDrop();
        JFrame frame = new JFrame("XPL Convert");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.add(panel());
        frame.pack();
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
    }

    /**
     * Builds the panel and checks the defaults without a display, so a broken build is caught
     * even on a machine where no window can open.
     */
    public static int selftest() {
        try {
            Gui g = new Gui();
            Options o = g.buildOptions(new ArrayList<>());
            if (!"sdk".equals(o.target)) {
                throw new IllegalStateException("default target should be sdk, got " + o.target);
            }
            if (!"arm64".equals(o.arch)) {
                throw new IllegalStateException("default arch should be arm64, got " + o.arch);
            }
            if (!o.archSuffix || !o.vstSubdir) {
                throw new IllegalStateException("defaults should be arch suffix + VST/ subfolder (build.py layout)");
            }
            if (!g.archSuffixBox.isSelected()) {
                throw new IllegalStateException("arch-suffix checkbox should start selected");
            }
            if (o.installDir != null || o.out != null || !o.extraKeys.isEmpty() || o.dryRun) {
                throw new IllegalStateException("unexpected non-default options: installDir="
                        + o.installDir + " out=" + o.out + " extraKeys=" + o.extraKeys
                        + " dryRun=" + o.dryRun);
            }
            if (!Options.SDK_PARENT.equals(o.sdkParent)) {
                throw new IllegalStateException("SDK layout should start with parent "
                        + Options.SDK_PARENT + ", got " + o.sdkParent);
            }
            g.origRadio.doClick();
            Options back = g.buildOptions(new ArrayList<>());
            if (!"original".equals(back.target) || !"-orig".equals(back.suffix)) {
                throw new IllegalStateException("classic target should set suffix -orig, got "
                        + back.target + " / " + back.suffix);
            }
            if (!Options.ORIG_PARENT.equals(back.origParent)) {
                throw new IllegalStateException("classic layout should switch the parent field to "
                        + Options.ORIG_PARENT + ", got " + back.origParent);
            }
            System.out.println("gui selftest: panel built, defaults ok");
            return 0;
        } catch (RuntimeException e) {
            System.out.println("gui selftest FAILED: " + e);
            e.printStackTrace(System.out);
            return 1;
        }
    }
}
