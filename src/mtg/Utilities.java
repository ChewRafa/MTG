package mtg;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.net.UnknownHostException;
import java.text.DecimalFormat;
import java.util.Calendar;

/**
 * @author Jaroslaw Pawlak
 */
public class Utilities {

    private static DecimalFormat df2 = new DecimalFormat("00");

    private static final int TIMEOUT = 300;
    private static String IP;
    private static long IPtime;

    private Utilities() {}

    /**
     * Looks for a file with given name (with no extension) in directory
     * {@link Main#CARDS} and all its subdirectories. Returns a total path
     * of file found or null.
     * @param name file name without extension
     * @return file path or null if not found
     */
    public static String findPath(String name) {
        return findPath(Main.CARDS, name);
    }

    /**
     * Looks for a file with given name (with no extension) in specified
     * directory (and subdirectories) and returns a total path of file found.
     * @param directory top directory to search
     * @param name file name without extension
     * @return total path or null if file not found
     */
    private static String findPath(File directory, String name) {
        for (File e : directory.listFiles()) {
            if (e.isFile() && Utilities.getName(e).equalsIgnoreCase(name)) {
                return e.getPath();
            }
            if (e.isDirectory()) {
                String x = findPath(e, name);
                if (x != null) {
                    return x;
                }
            }
        }
        return null;
    }

    /**
     * Receives file from the socket and saves it to the specified file path
     * @param path where to save a file received
     * @param socket socket to read data from
     * @throws FileNotFoundException
     * @throws IOException
     */
    public static void receiveFile(File path, Socket socket)
            throws FileNotFoundException, IOException  {
        path.getParentFile().mkdirs();
        try (BufferedInputStream bis
                = new BufferedInputStream(socket.getInputStream());
                BufferedOutputStream bos
                = new BufferedOutputStream(new FileOutputStream(path))) {
            

            byte[] b = new byte[256];
            int read = -1;

            while ((read = bis.read(b)) >= 0) {
                bos.write(b, 0, read);
            }
        }
    }

    /**
     * Sends a file to the specified socket.
     * @param file file to be sent
     * @param socket socket to send to
     * @throws IOException
     */
    public static void sendFile(File file, Socket socket) throws IOException {
        try (BufferedOutputStream bos
                = new BufferedOutputStream(socket.getOutputStream());
                BufferedInputStream bis
                = new BufferedInputStream(new FileInputStream(file))) {
            byte[] b = new byte[256];
            int read = -1;
            while ((read = bis.read(b)) >= 0) {
                bos.write(b, 0, read);
            }
        }
    }

    /**
     * Returns file name without extension, e.g. <code>Forest</code> will be
     * returned for <code>C:/Forest.jpg</code>
     * @param file file which name will be returned
     * @return file name without extension
     */
    public static String getName(File file) {
        if (!file.getName().contains(".")) {
            return file.getName();
        } else {
            return file.getName().substring(0, file.getName().lastIndexOf("."));
        }
    }

    /**
     * Returns file extension (without ".") or null if file
     * is a directory or does not contain "."
     * @param file file
     * @return file extension or null if none
     */
    public static String getExtension(File file) {
        if (!file.isFile() || !file.getName().contains(".")) {
            return null;
        } else {
            return file.getName().substring(file.getName().lastIndexOf(".") + 1);
        }
    }

    /**
     * Returns current time for file names in a format 2011-06-15 23.03.48.
     * @return
     */
    public static String getCurrentTimeForFile() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.YEAR) + "-"
                + df2.format(c.get(Calendar.MONTH) + 1) + "-"
                + df2.format(c.get(Calendar.DAY_OF_MONTH)) + " "
                + df2.format(c.get(Calendar.HOUR_OF_DAY)) + "."
                + df2.format(c.get(Calendar.MINUTE)) + "."
                + df2.format(c.get(Calendar.SECOND));
    }

    /**
     * Returns external IP or error message. This method stores external IP
     * and do not download it again before <code>TIMEOUT</code> seconds have
     * passed.
     * @return external IP or error message
     */
    public static String getExternalIP() {
//        return "Disabled for testing";
        if (IP != null && System.currentTimeMillis() - IPtime < TIMEOUT * 1000) {
            return IP;
        }
        try {
            URL url = new URL("http://checkip.dyndns.org/");
            InputStream is = url.openStream();
            InputStreamReader isr = new InputStreamReader(is);
            BufferedReader br = new BufferedReader(isr);
            IPtime = System.currentTimeMillis();
            return IP = br.readLine()
                    .replace("<html><head><title>Current IP Check</title></head><body>Current IP Address: ", "")
                    .replace("</body></html>", "");
        } catch (Exception ex) {
            IP = null;
            return "Could not connect with http://checkip.dyndns.org/";
        }
    }

    public static String getInternalIP() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException ex) {
            return null;
        }
    }
    
    /**
     * Removes all non-word characters from the string and returns
     * first 15 characters of it.
     * @param name name to be checked
     * @return modified name
     */
    public static String checkName(String name) {
        name = name.replaceAll("\\W", "");
        if (name.length() == 0) {
            name = "PLAYER";
        } else if (name.length() > 15) {
            name = name.substring(0, 15);
        }
        return name;
    }

    /**
     * Resizes BufferedImage to height equal <code>h</code> and
     * width equal <code>w</code>
     * @param org original BufferedImage
     * @param w requested width
     * @param h requested height
     * @return scaled BufferedImage
     */
    public static BufferedImage resize(BufferedImage org, int w, int h) {
        BufferedImage scaledImage = new BufferedImage(
                w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics2D = scaledImage.createGraphics();
        graphics2D.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics2D.drawImage(org, 0, 0, w, h, null);
        graphics2D.dispose();
        return scaledImage;
    }

    /**
     * Resizes and rotates the BufferedImage by 90 degrees.
     * @param org original BufferedImage
     * @param w requested width
     * @param h requested height
     * @return rotated BufferedImage with width equal <code>h</code>
     * and height equal <code>w</code>
     */
    public static BufferedImage rotate(BufferedImage org, int w, int h) {
        BufferedImage rotated = new BufferedImage(
            h, w, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics2D = rotated.createGraphics();
        graphics2D.rotate(Math.toRadians(90), h / 2, h / 2);
        graphics2D.drawImage(resize(org, w, h), 0, 0, w, h, 0, 0, w, h, null);
        graphics2D.dispose();
        return rotated;
    }
}
