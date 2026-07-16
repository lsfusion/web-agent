package com.lsfusion.webagent;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

// System tray icon with a minimal menu. The agent is a background process with
// no other UI, so the tray is the only visible sign it's running and the only
// built-in way to stop it. The menu is a Swing JPopupMenu styled by FlatLaf and
// following the OS light/dark theme.
public final class Tray {

    private static final Logger LOG = Logger.getLogger(Tray.class.getName());

    private Tray() {
    }

    public static void install(String host, int port) {
        if (!SystemTray.isSupported()) {
            LOG.info("system tray not supported — running without tray icon");
            return;
        }
        try {
            TrayIcon icon = new TrayIcon(loadIcon(), "lsFusion Web Agent");
            icon.setImageAutoSize(true);
            icon.addMouseListener(new MouseAdapter() {
                // popup trigger fires on press on Linux, on release on Windows
                @Override
                public void mousePressed(MouseEvent e) {
                    maybeShowMenu(e);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    maybeShowMenu(e);
                }

                private void maybeShowMenu(MouseEvent e) {
                    // left click opens the menu too; it is handled on release only,
                    // since this method runs for both press and release
                    boolean leftClick = e.getID() == MouseEvent.MOUSE_RELEASED && e.getButton() == MouseEvent.BUTTON1;
                    // tray MouseEvents carry screen coordinates: there is no component
                    if (e.isPopupTrigger() || leftClick)
                        SwingUtilities.invokeLater(() -> showMenu(host, port, e.getPoint()));
                }
            });
            SystemTray.getSystemTray().add(icon);
        } catch (Exception e) {
            // tray is a convenience — never let it take the agent down
            LOG.log(Level.WARNING, "failed to install tray icon", e);
        }
    }

    private static void showMenu(String host, int port, Point screenPoint) {
        try {
            applyTheme();

            JPopupMenu menu = new JPopupMenu();
            JMenuItem info = new JMenuItem("web-agent " + WebAgentServer.VERSION + " on " + host + ":" + port);
            info.setEnabled(false);
            menu.add(info);
            menu.addSeparator();
            JMenuItem logs = new JMenuItem("Open logs folder");
            logs.addActionListener(e -> openLogsFolder());
            menu.add(logs);
            JMenuItem exit = new JMenuItem("Exit");
            // the shutdown hook in Main stops the HTTP server
            exit.addActionListener(e -> System.exit(0));
            menu.add(exit);

            // JPopupMenu needs a focusable owner to close itself on an outside
            // click; an invisible 0x0 dialog at the cursor plays that role.
            JDialog anchor = new JDialog((java.awt.Frame) null);
            anchor.setUndecorated(true);
            anchor.setAlwaysOnTop(true);
            anchor.addWindowFocusListener(new WindowAdapter() {
                @Override
                public void windowLostFocus(WindowEvent e) {
                    menu.setVisible(false);
                }
            });
            menu.addPopupMenuListener(new PopupMenuListener() {
                @Override
                public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                }

                @Override
                public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                    anchor.dispose();
                }

                @Override
                public void popupMenuCanceled(PopupMenuEvent e) {
                    anchor.dispose();
                }
            });

            // open towards the screen: the tray lives at an edge, so flip the
            // menu when the cursor is too close to the bottom/right border
            Dimension size = menu.getPreferredSize();
            Rectangle screen = anchor.getGraphicsConfiguration() != null
                    ? anchor.getGraphicsConfiguration().getBounds()
                    : new Rectangle(java.awt.Toolkit.getDefaultToolkit().getScreenSize());
            int x = screenPoint.x, y = screenPoint.y;
            if (x + size.width > screen.x + screen.width) x -= size.width;
            if (y + size.height > screen.y + screen.height) y -= size.height;

            anchor.setBounds(x, y, 0, 0);
            anchor.setVisible(true);
            menu.show(anchor.getContentPane(), 0, 0);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "failed to show tray menu", e);
        }
    }

    // Re-read the OS theme on every menu open, so a light/dark switch is picked
    // up without restarting the agent (FlatLaf setup is cheap and idempotent).
    private static void applyTheme() {
        boolean dark = isSystemDark();
        boolean flatDark = UIManager.getLookAndFeel() instanceof FlatDarkLaf;
        boolean flatLight = UIManager.getLookAndFeel() instanceof FlatLightLaf;
        if (dark && !flatDark) FlatDarkLaf.setup();
        else if (!dark && !flatLight) FlatLightLaf.setup();
    }

    // Windows keeps the apps theme in AppsUseLightTheme (0 = dark); reg.exe is
    // the only stock way to read it without native code. Anywhere else default
    // to light until Linux builds bring a GTK/gsettings probe.
    private static boolean isSystemDark() {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) return false;
        try {
            Process p = new ProcessBuilder("reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "AppsUseLightTheme").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            return out.contains("0x0");
        } catch (Exception e) {
            return false;
        }
    }

    // A themed modal dialog for a fatal startup problem, shown before the tray
    // exists. Always-on-top with a temporary owner, since a background process'
    // dialog otherwise opens behind every window. No-op (logged) when headless.
    static void showStartupError(String message) {
        try {
            applyTheme();
            JFrame owner = new JFrame();
            owner.setUndecorated(true);
            owner.setAlwaysOnTop(true);
            owner.setLocationRelativeTo(null);
            owner.setVisible(true);
            JOptionPane.showMessageDialog(owner, message, "lsFusion Web Agent", JOptionPane.WARNING_MESSAGE);
            owner.dispose();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "failed to show startup error dialog", e);
        }
    }

    private static void openLogsFolder() {
        try {
            Path dir = Logging.logDir();
            // the dir may not exist yet if file logging failed to initialize
            Files.createDirectories(dir);
            Desktop.getDesktop().open(dir.toFile());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "failed to open logs folder", e);
        }
    }

    private static Image loadIcon() throws IOException {
        try (InputStream in = Tray.class.getResourceAsStream("/tray-icon.png")) {
            if (in == null) throw new IOException("tray-icon.png resource missing");
            return ImageIO.read(in);
        }
    }
}
