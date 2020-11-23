package to.us.kevinraneri.picturematcher;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.makernotes.SonyType1MakernoteDirectory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static java.lang.String.format;

public class PictureMatcher {

    public static void main(String[] args) {
        String path = args[0];

        System.out.println("Scanning for files in " + path);

        List<File> jpegFiles = getFilesByExtensionType(path, ".JPG");

        if (jpegFiles.size() > 0) {
            System.out.println("Found " + jpegFiles.size() + " JPEG images");
        } else {
            System.out.println("Didn't find any JPEG images.");
            System.exit(0);
        }

        System.out.println("Generating hash tables...");

        Map<String, Set<File>> fileHashes = new HashMap<>();

        AtomicInteger finishedFiles = new AtomicInteger(0);
        for (File jpegFile : jpegFiles) {
            printProgressBar(finishedFiles.getAndIncrement(), jpegFiles.size(), 48);

            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(baos);

                Metadata meta = ImageMetadataReader.readMetadata(jpegFile);

                ExifSubIFDDirectory directory = meta.getFirstDirectoryOfType(ExifSubIFDDirectory.class);

                if (directory == null) {
                    System.out.println(format("File %s has no exif data!", jpegFile.getAbsolutePath()));
                    continue;
                }

                Date date = directory.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL);

                if (date == null) {
                    System.out.print("                                                            \r");
                    System.out.println(format("Group %s has no date!", jpegFile.getAbsolutePath()));
                    continue;
                }

                dos.writeLong(date.getTime());

                String camera = meta.getFirstDirectoryOfType(ExifIFD0Directory.class).getString(ExifIFD0Directory.TAG_MODEL);

                if (camera == null) camera = "";

                dos.writeChars(camera);

                Directory sonyDir = meta.getFirstDirectoryOfType(SonyType1MakernoteDirectory.class);
                if (sonyDir == null || sonyDir.getByteArray(0x9400) == null) {
                    System.out.print("                                                            \r");
                    System.out.println(format("File %s has no sony data!", jpegFile.getAbsolutePath()));
                    continue;
                }

                dos.write(sonyDir.getByteArray(0x9400));

                dos.flush();
                dos.close();
                digest.update(baos.toByteArray());
                String hash = new String(digest.digest());

                Set<File> matchingSet = fileHashes.get(hash);
                if (matchingSet == null) {
                    matchingSet = new HashSet<>();
                    fileHashes.put(hash, matchingSet);
                }

                matchingSet.add(jpegFile);
            } catch (ImageProcessingException | IOException | NoSuchAlgorithmException e) {
                System.out.print("                                                            \r");
                System.out.println(format("Unable to read EXIF data for file %s", jpegFile.getAbsolutePath()));
            }
        }

        System.out.print("                                                            \r");
        System.out.println("Determining new file names...");

        Map<File, String> newNames = new HashMap<>();

        for (Set<File> fileGroup : fileHashes.values()) {
            if (fileGroup.size() <= 1) {
                continue;
            }

            // Determine which file is the "deepest" within subdirectories. This is the target we want to rename to.
            int deepestFileDirs = 0;
            File deepestFile = null;
            for (File file : fileGroup) {
                int dirs = 1;
                File parent = file.getParentFile();
                while (parent != null) {
                    dirs++;
                    parent = parent.getParentFile();
                }
                if (dirs > deepestFileDirs) {
                    deepestFileDirs = dirs;
                    deepestFile = file;
                }
            }

            String targetName = removeFileExtension(deepestFile.getName());

            for (File file : fileGroup) {
                String finalPath = removeFileExtension(file.getAbsolutePath());
                String[] pieces = finalPath.split("\\\\");
                pieces[pieces.length - 1] = targetName;
                pieces[pieces.length - 1] += file.getName().substring(removeFileExtension(file.getName()).length()).toLowerCase(); // Tack on file extension
                finalPath = String.join("\\", pieces);

                if (new File(finalPath).exists()) {
                    if (!finalPath.equalsIgnoreCase(file.getAbsolutePath())) {
                        // Only print the warning message if the file isn't being renamed to itself.
                        System.out.println("Attempting to rename to a file that already exists");
                        System.out.println(format("%s -> %s", file.getAbsolutePath(), finalPath));
                    }
                    continue;
                }

                newNames.put(file, finalPath);
            }
        }

        if (newNames.size() == 0) {
            System.out.println("No files to rename. Nothing to do.");
            return;
        }
        System.out.println("File names calculated. Writing mapping file.");
        File mappingFile = new File(Paths.get(path).toAbsolutePath() + "/mapping.txt");
        try {
            if (!mappingFile.exists()) mappingFile.createNewFile();
            PrintWriter writer = new PrintWriter(new FileOutputStream(mappingFile));

            for (Map.Entry<File, String> fileStringEntry : newNames.entrySet()) {
                File file = fileStringEntry.getKey();
                String finalPath = fileStringEntry.getValue();
                writer.write(format("move \"%s\" \"%s\"%n", file.getAbsolutePath().substring(path.length() + 1), finalPath.substring(path.length() + 1)));
            }
            writer.close();
            System.out.println(format("Mapping file written to %s", mappingFile.getAbsolutePath()));
        } catch (IOException e) {
            e.printStackTrace();
            System.out.println("Failed to write mapping file.");
        }

        System.out.println(String.format("Will rename %d files (%d groups)", newNames.size(), fileHashes.values().stream().filter(c -> c.size() > 1).count()));

        Scanner input = new Scanner(System.in);
        System.out.print("Would you like to continue? [Y/N] ");
        if (!input.nextLine().equalsIgnoreCase("Y")) return;

        System.out.println("Renaming files. Do not exit.");

        int i = 0;
        for (Map.Entry<File, String> fileStringEntry : newNames.entrySet()) {
            printProgressBar(i, newNames.size(), 48);
            fileStringEntry.getKey().renameTo(new File(fileStringEntry.getValue()));
            i++;
        }

        System.out.print("                                                            \r");
        System.out.println("Finished!");
    }

    public static List<File> getFilesByExtensionType(String dirpath, String ext) {
        try {
            AtomicInteger i = new AtomicInteger(0);
            return Files.walk(Paths.get(dirpath))
                    .filter(Files::isRegularFile)
                    .filter((path) ->
                            path.toFile().getName().toUpperCase().endsWith(ext.toUpperCase()))
                    .map(Path::toFile)
                    .filter(path -> {
                        System.out.print("Found " + i.incrementAndGet() + " files\r");
                        return true;
                    })
                    .collect(Collectors.toList());
        } catch (IOException e) {
            System.err.println("Error scanning directory!");
            e.printStackTrace();
            return new ArrayList<>(); // Return an empty list.
        }
    }

    public static void printProgressBar(int completed, int total, int length) {
        double progress = (double) completed / total;
        System.out.print("[");
        for (int i = 0; i < length; i++)
            System.out.print(progress*length < i ? " " : "=");
        System.out.print("] " + Math.round(progress * 1000)/10d + "%\r");
    }

    public static String removeFileExtension(String file) {
        return file.substring(0, file.lastIndexOf("."));
    }

}
