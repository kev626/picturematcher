package to.us.kevinraneri.picturematcher;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.makernotes.SonyType1MakernoteDirectory;

import com.sun.org.apache.xerces.internal.impl.dv.util.HexBin;
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
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static java.lang.String.format;

public class PictureMatcher {
    
    public static final int[] tags = new int[] {
            0x829A,
            0x829D,
            0x8827,
            0x8832,
            0x9003,
            0x9004,
            0x9203,
            0x9204,
            0x9205,
            0x920A,
            0x9290,
            0x9291,
            0x9292,
    };

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
    
        Map<File, byte[]> fileToSonyHash = new HashMap<>();
        Map<File, byte[]> fileToBaseHash = new HashMap<>();

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
                dos.flush();
                dos.close();
                byte[] initialData = baos.toByteArray();
                baos.close();

                // Now we want to hash up the "base" exif data
                ByteArrayOutputStream baseBaos = new ByteArrayOutputStream();
                DataOutputStream baseDos = new DataOutputStream(baseBaos);
    
                for (int tag : tags) {
                    String str = directory.getString(tag);
                    if (str == null) continue;
                    
                    byte[] bytes = str.getBytes();
                    baseDos.write(bytes);
                }
                baseDos.flush();
                baseDos.close();
                digest.update(baseBaos.toByteArray());
                byte[] hash = digest.digest();
                baseBaos.close();
                
                fileToBaseHash.put(jpegFile, hash);
                //System.out.println("BASE: " + jpegFile + " | " + HexBin.encode(hash));
    
                Directory sonyDir = meta.getFirstDirectoryOfType(SonyType1MakernoteDirectory.class);
                if (sonyDir != null && sonyDir.getByteArray(0x9400) != null) {
                    ByteArrayOutputStream sonyBaos = new ByteArrayOutputStream();
                    DataOutputStream sonyDos = new DataOutputStream(sonyBaos);
        
                    digest.reset();
                    sonyDos.write(initialData);
                    sonyDos.write(sonyDir.getByteArray(0x9400));
                    sonyDos.write(hash);
                    sonyDos.flush();
                    sonyDos.close();
                    digest.update(sonyBaos.toByteArray());
                    byte[] sonyHash = digest.digest();
                    digest.reset();
                    sonyBaos.close();
        
                    fileToSonyHash.put(jpegFile, sonyHash);
                    //System.out.println("SONY: " + jpegFile + " | " + HexBin.encode(sonyHash));
                }
            } catch (ImageProcessingException | IOException | NoSuchAlgorithmException e) {
                System.out.print("                                                            \r");
                System.out.println(format("Unable to read EXIF data for file %s", jpegFile.getAbsolutePath()));
            }
        }
    
        System.out.print("                                                            \r");
        System.out.println("Building file group index...");
    
        List<Group> groups = new ArrayList<>();
    
        for (Entry<File, byte[]> entry : fileToSonyHash.entrySet()) {
            // First we find a matching group for the sony hash, or create one if it hasn't been indexed.
            Group target = null;
            for (Group group : groups) {
                if (group.matchesSony(entry.getValue())) {
                    target = group;
                }
            }
            if (target == null) {
                groups.add(target = new Group());
                target.setSonyHash(entry.getValue());
            }
            
            target.getFiles().add(entry.getKey());
        }
        System.out.println("Indexed " + fileToSonyHash.size() + " files by sony hash");
    
        for (Group group : groups) {
            for (File file : group.getFiles()) {
                byte[] base = fileToBaseHash.get(file);
                if (base == null) continue;
                
                if (group.getBaseHash() == null) {
                    group.setBaseHash(base);
                    continue;
                }
                
                if (!group.matchesBase(base)) {
                    group.setIgnored(true);
                    System.out.println("Group had a sony match and base mismatch!");
                }
            }
        }
    
        for (Entry<File, byte[]> entry : fileToBaseHash.entrySet()) {
            if (fileToSonyHash.containsKey(entry.getKey())) continue;
            Group target = null;
            for (Group group : groups) {
                if (group.matchesBase(entry.getValue())) {
                    target = group;
                    continue;
                }
            }
            
            if (target == null) {
                System.out.println("File had no matching groups.");
                continue;
            }
            
            target.getFiles().add(entry.getKey());
        }
    
        for (Group group : groups) {
            if (group.getFiles().size() > 2) {
                String finalName = group.getFinalName();
                // There can only be one file that doesn't match the final name.
                int matching = 0;
                for (File file : group.getFiles()) {
                    if (!removeFileExtension(file.getName()).equalsIgnoreCase(finalName)) {
                        matching++;
                    }
                }
                if (matching > 1) {
                    System.out.println("Group " + group.getFinalName() + " had more than two files! Skipping!");
                    group.setIgnored(true);
                    continue;
                }
            }
            
            if (group.getFiles().size() < 2) {
                //System.out.println("Group had less than two files! Skipping!");
                group.setIgnored(true);
                continue;
            }
        }
        
        int renamingGroups = (int) groups.stream().filter(g -> !g.isIgnored()).count();
        System.out.println("Renaming files in " + renamingGroups + " groups.");
        
        // Now generate the mappings, write the mapping file, and rename.
        Map<File, String> newNames = new HashMap<>();
    
        int correctFiles = 0;
        for (Group group : groups) {
            if (group.isIgnored()) continue;
            
            String name = group.getFinalName();
            for (File file : group.getFiles()) {
                String finalPath = removeFileExtension(file.getAbsolutePath());
                String[] pieces = finalPath.split("\\\\");
                pieces[pieces.length - 1] = name;
                pieces[pieces.length - 1] += file.getName().substring(removeFileExtension(file.getName()).length()).toLowerCase(); // Tack on file extension
                finalPath = String.join("\\", pieces);
        
                if (new File(finalPath).exists()) {
                    if (!finalPath.equalsIgnoreCase(file.getAbsolutePath())) {
                        // Only print the warning message if the file isn't being renamed to itself.
                        System.out.println("Attempting to rename to a file that already exists");
                        System.out.println(format("%s -> %s", file.getAbsolutePath(), finalPath));
                    } else {
                        correctFiles++;
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
    
        System.out.println("File names calculated. There are " + correctFiles + " files with correct names already.");
        System.out.println("Writing mapping file...");
        File mappingFile = new File(Paths.get(path).toAbsolutePath() + "/matching.txt");
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
    
        System.out.println(String.format("Will rename %d files (%d groups)", newNames.size(), groups.stream().filter(g -> !g.isIgnored()).count()));
    
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
