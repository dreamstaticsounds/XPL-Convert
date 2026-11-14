package xplconvert;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.UIManager;

/**
 * The window's dark colour scheme: Nimbus (bundled with Java, so still no dependencies)
 * re-coloured from a graphite palette with a blue accent.
 *
 * <p>Order of operations matters, because of how Nimbus wires its palette:</p>
 * <ol>
 *   <li>Palette keys go in <em>before</em> Nimbus is installed.  Nimbus computes its
 *       derived colours at creation time by reading {@code UIManager}, which consults
 *       this table first -- so every derived colour starts out dark.  (Installing Nimbus
 *       first and putting the palette afterwards loses: several palette entries such as
 *       {@code text}, {@code infoText} and {@code controlText} share one derived-colour
 *       object, and re-putting them re-parents it in Nimbus' colour tree so later
 *       updates no longer reach {@code textForeground}, leaving text black.)</li>
 *   <li>Component keys go in <em>after</em>, written into the look-and-feel's own
 *       defaults table so the stock light values are replaced rather than duplicated --
 *       a duplicate would make the value Nimbus reads back a coin-flip.</li>
 * </ol>
 */
public final class Theme {
    private Theme() {
    }

    /** Window and panel surfaces. */
    private static final Color PANEL = new Color(0x24282F);
    /** Buttons, scrollbar bodies, and other raised controls. */
    private static final Color RAISED = new Color(0x3A404A);
    /** Sunken controls: combo boxes, wells. */
    private static final Color SUNKEN = new Color(0x2C313A);
    /** Editable text backgrounds: fields, lists. */
    private static final Color FIELD = new Color(0x1B1F25);
    /** The report area, darkest of all so it reads as a log. */
    private static final Color LOG = new Color(0x15181D);
    private static final Color LOG_TEXT = new Color(0xCBD3DC);
    /** Selected text / list rows. */
    private static final Color SELECTION = new Color(0x35669C);
    /** Focus ring and other bright accents. */
    private static final Color ACCENT = new Color(0x7FB2FF);
    private static final Color TEXT = new Color(0xE8EBEF);
    private static final Color TEXT_DIM = new Color(0x98A0AA);
    private static final Color BORDER = new Color(0x3A404A);

    public static void install() {
        palette();
        installNimbus();
        componentDefaults();
    }

    /** Nimbus' own palette keys -- these drive the painters and every derived colour. */
    private static void palette() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("nimbusBase", PANEL);
        c.put("nimbusBlueGrey", RAISED);
        c.put("nimbusLightBackground", SUNKEN);
        c.put("nimbusSelectionBackground", SELECTION);
        c.put("nimbusSelectionForeground", TEXT);
        c.put("nimbusSelectedText", TEXT);
        c.put("nimbusForeground", TEXT);
        c.put("nimbusDisabledText", TEXT_DIM);
        c.put("nimbusFocus", ACCENT);
        c.put("nimbusBorder", BORDER);
        // classic Swing keys for everything that reads colours by name
        c.put("control", SUNKEN);
        c.put("controlText", TEXT);
        c.put("info", RAISED);
        c.put("infoText", TEXT);
        // "text" is Nimbus' base *text colour* constant (its global textForeground derives
        // from it) -- unlike Metal's "text", it is not a background
        c.put("text", TEXT);
        c.put("textText", TEXT);
        c.put("textHighlight", SELECTION);
        c.put("textInactiveText", TEXT_DIM);
        c.put("caret", TEXT);

        for (Map.Entry<String, Object> e : c.entrySet()) {
            UIManager.put(e.getKey(), e.getValue());
        }
    }

    private static void installNimbus() {
        try {
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (Exception e) {
            // no Nimbus in this runtime: the colour keys still apply as far as they can
        }
    }

    /**
     * Component keys cover the parts Nimbus keeps for itself (panel backgrounds,
     * list/field colours, titled borders, the report area).  Each stock value is
     * removed from every defaults table first, so exactly one value -- ours -- is
     * left for Nimbus to find.
     */
    private static void componentDefaults() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("Panel.background", PANEL);
        c.put("Panel.foreground", TEXT);
        c.put("Label.foreground", TEXT);
        c.put("TitledBorder.titleColor", TEXT);
        c.put("Button.foreground", TEXT);
        c.put("CheckBox.foreground", TEXT);
        c.put("RadioButton.foreground", TEXT);
        c.put("ScrollPane.background", PANEL);
        c.put("Viewport.background", FIELD);
        c.put("List.background", FIELD);
        c.put("List.foreground", TEXT);
        c.put("List.selectionBackground", SELECTION);
        c.put("List.selectionForeground", TEXT);
        c.put("List[Selected].textForeground", TEXT);
        c.put("ComboBox.background", new Color(0x000000)); // painter brightens bg +0.25..+0.44; start at black
        c.put("ComboBox.foreground", TEXT);
        c.put("TextField.background", FIELD);
        c.put("TextField.foreground", TEXT);
        c.put("TextField.caretForeground", TEXT);
        c.put("TextField.selectionBackground", SELECTION);
        c.put("TextField.selectionForeground", TEXT);
        c.put("TextArea.background", LOG);
        c.put("TextArea.foreground", LOG_TEXT);
        c.put("TextArea.caretForeground", LOG_TEXT);
        c.put("TextArea.selectionBackground", SELECTION);
        c.put("TextArea.selectionForeground", TEXT);
        c.put("ToolTip.background", RAISED);
        c.put("ToolTip.foreground", TEXT);
        c.put("FileChooser.background", PANEL);
        c.put("OptionPane.background", PANEL);

        for (Map.Entry<String, Object> e : c.entrySet()) {
            UIManager.getDefaults().remove(e.getKey());
            UIManager.put(e.getKey(), e.getValue());
        }
    }
}
