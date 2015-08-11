package com.rjrudin.marklogic.modulesloader.impl;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.marklogic.client.DatabaseClient;
import com.marklogic.client.document.DocumentWriteSet;
import com.marklogic.client.document.GenericDocumentManager;
import com.marklogic.client.io.DocumentMetadataHandle;
import com.marklogic.client.io.DocumentMetadataHandle.Capability;
import com.marklogic.client.io.FileHandle;
import com.marklogic.client.io.Format;
import com.rjrudin.marklogic.client.FilenameUtil;
import com.rjrudin.marklogic.client.LoggingObject;
import com.rjrudin.marklogic.modulesloader.ModulesManager;

public class DocumentsLoader extends LoggingObject implements FileVisitor<Path> {

    private DatabaseClient client;
    private ModulesManager modulesManager;

    private GenericDocumentManager docManager;
    private DocumentWriteSet writeSet;
    private int filesWritten = 0;
    private int batchSize = 300;

    // Default permissions and collections for each module
    private String permissions = "rest-admin,read,rest-admin,update,rest-extension-user,execute";
    private String[] collections;

    private Path currentAssetPath;
    private Set<File> filesLoaded;

    public DocumentsLoader(DatabaseClient client) {
        this.client = client;
    }

    public Set<File> loadAssetsViaDocumentsEndpoint(String... paths) {
        this.filesLoaded = new HashSet<>();
        this.docManager = client.newDocumentManager();
        this.writeSet = docManager.newWriteSet();
        this.filesWritten = 0;

        for (String path : paths) {
            logger.info(format("Loading assets from path: %s", path));
            this.currentAssetPath = Paths.get(path);
            try {
                Files.walkFileTree(this.currentAssetPath, this);
            } catch (IOException ie) {
                throw new RuntimeException(format("Error while walking assets file tree: %s", ie.getMessage()), ie);
            }
        }

        if (filesWritten % batchSize > 0) {
            logger.info("Writing batch at end of loading process");
            docManager.write(writeSet);
        }

        return filesLoaded;
    }

    @Override
    public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
        if (attrs.isRegularFile()) {
            File file = path.toFile();
            if (modulesManager != null && !modulesManager.hasFileBeenModifiedSinceLastInstalled(file)) {
                return FileVisitResult.CONTINUE;
            }
            Path relPath = currentAssetPath.relativize(path);
            String uri = "/" + relPath.toString().replace("\\", "/");
            logger.info("Loading file: " + file.getAbsolutePath());
            loadFile(uri, file);
            filesLoaded.add(file);

            if (modulesManager != null) {
                modulesManager.saveLastInstalledTimestamp(file, new Date());
            }
        }
        return FileVisitResult.CONTINUE;
    }

    protected void loadFile(String uri, File f) {
        DocumentMetadataHandle metadata = new DocumentMetadataHandle().withPermission("rest-reader",
                new Capability[] {}).withPermission("rest-writer", new Capability[] {});
        if (collections != null && collections.length > 0) {
            metadata = metadata.withCollections(collections);
        }
        if (permissions != null && permissions.trim().length() > 0) {
            Map<String, Set<Capability>> map = new HashMap<String, Set<Capability>>();
            String[] tokens = permissions.split(",");
            for (int i = 0; i < tokens.length; i += 2) {
                String role = tokens[i];
                Capability capability = Capability.valueOf(tokens[i + 1].toUpperCase());
                if (map.containsKey(role)) {
                    map.get(role).add(capability);
                } else {
                    Set<Capability> set = new HashSet<>();
                    set.add(capability);
                    map.put(role, set);
                }
            }
            logger.info("map: " + map);
            for (String key : map.keySet()) {
                metadata = metadata.withPermission(key, map.get(key).toArray(new Capability[] {}));
            }
        }
        logger.info("perms: " + metadata.getPermissions().keySet());
        writeSet.add(uri, metadata, new FileHandle(f).withFormat(determineFormat(f)));
        filesWritten++;
        if (filesWritten % batchSize == 0) {
            logger.info("Writing batch");
            docManager.write(writeSet);
            writeSet = docManager.newWriteSet();
        }
    }

    protected Format determineFormat(File file) {
        String name = file.getName();
        if (FilenameUtil.isXslFile(name) || name.endsWith(".xml") || name.endsWith(".html")) {
            return Format.XML;
        } else if (name.endsWith(".swf") || name.endsWith(".jpeg") || name.endsWith(".jpg") || name.endsWith(".png")
                || name.endsWith(".gif") || name.endsWith(".svg") || name.endsWith(".ttf") || name.endsWith(".eot")
                || name.endsWith(".woff") || name.endsWith(".cur")) {
            return Format.BINARY;
        }
        return Format.TEXT;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
        return FileVisitResult.CONTINUE;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public void setModulesManager(ModulesManager modulesManager) {
        this.modulesManager = modulesManager;
    }

}
