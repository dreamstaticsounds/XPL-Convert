package xplconvert;

import javax.swing.SwingUtilities;

/** Entry point: arguments run the CLI, no arguments open the GUI. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        Theme.install();
        if (args.length > 0) {
            if ("--help".equals(args[0]) || "-h".equals(args[0])) {
                Cli.usage(System.out);
                return;
            }
            if ("--selftest".equals(args[0])) {
                System.exit(Gui.selftest());
                return;
            }
            System.exit(Cli.run(args));
            return;
        }
        SwingUtilities.invokeLater(() -> new Gui().show());
    }
}
