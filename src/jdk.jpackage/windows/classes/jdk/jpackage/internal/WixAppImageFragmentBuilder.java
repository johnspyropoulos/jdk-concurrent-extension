/*
 * Copyright (c) 2019, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package jdk.jpackage.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.transform.dom.DOMResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import jdk.jpackage.internal.AppImageFile.LauncherInfo;
import jdk.jpackage.internal.util.XmlConsumer;
import static jdk.jpackage.internal.StandardBundlerParam.APP_NAME;
import static jdk.jpackage.internal.StandardBundlerParam.INSTALL_DIR;
import static jdk.jpackage.internal.StandardBundlerParam.VENDOR;
import static jdk.jpackage.internal.StandardBundlerParam.VERSION;
import static jdk.jpackage.internal.WinMsiBundler.MSI_SYSTEM_WIDE;
import static jdk.jpackage.internal.WinMsiBundler.SERVICE_INSTALLER;
import static jdk.jpackage.internal.WinMsiBundler.WIN_APP_IMAGE;
import jdk.jpackage.internal.WixToolset.WixToolsetType;
import jdk.jpackage.internal.model.DottedVersion;
import jdk.jpackage.internal.util.PathGroup;
import jdk.jpackage.internal.util.PathUtils;
import jdk.jpackage.internal.util.XmlUtils;
import org.w3c.dom.NodeList;

/**
 * Creates WiX fragment with components for contents of app image.
 */
class WixAppImageFragmentBuilder extends WixFragmentBuilder {

    @Override
    void initFromParams(Map<String, ? super Object> params) {
        super.initFromParams(params);

        Path appImageRoot = WIN_APP_IMAGE.fetchFrom(params);

        Supplier<ApplicationLayout> appImageSupplier = () -> {
            if (StandardBundlerParam.isRuntimeInstaller(params)) {
                return ApplicationLayout.javaRuntime();
            } else {
                return ApplicationLayout.platformAppImage();
            }
        };

        systemWide = MSI_SYSTEM_WIDE.fetchFrom(params);

        registryKeyPath = Path.of("Software",
                VENDOR.fetchFrom(params),
                APP_NAME.fetchFrom(params),
                VERSION.fetchFrom(params)).toString();

        installDir = (systemWide ? PROGRAM_FILES : LOCAL_PROGRAM_FILES).resolve(
                WINDOWS_INSTALL_DIR.fetchFrom(params));

        do {
            ApplicationLayout layout = appImageSupplier.get();
            // Don't want AppImageFile.FILENAME in installed application.
            new InstallableFile(AppImageFile.getPathInAppImage(Path.of("")),
                    null).excludeFromApplicationLayout(layout);

            // Want absolute paths to source files in generated WiX sources.
            // This is to handle scenario if sources would be processed from
            // different current directory.
            appImage = layout.resolveAt(appImageRoot.toAbsolutePath().normalize());
        } while (false);

        installedAppImage = appImageSupplier.get().resolveAt(INSTALLDIR);

        shortcutFolders = Stream.of(ShortcutsFolder.values()).filter(
                shortcutFolder -> shortcutFolder.requested(params)).collect(
                        Collectors.toSet());

        if (StandardBundlerParam.isRuntimeInstaller(params)) {
            launchers = Collections.emptyList();
        } else {
            launchers = AppImageFile.getLaunchers(appImageRoot, params);
        }

        launchersAsServices = launchers.stream()
                .filter(LauncherInfo::isService)
                .map(launcher -> {
                    var launcherPath = addExeSuffixToPath(
                    installedAppImage.launchersDirectory().resolve(
                            launcher.getName()));
                    var id = Id.File.of(launcherPath);
                    return new WixLauncherAsService(launcher.getName(), params)
                            .setLauncherInstallPath(toWixPath(launcherPath))
                            .setLauncherInstallPathId(id);
                }).toList();

        if (!launchersAsServices.isEmpty()) {
            serviceInstaller = SERVICE_INSTALLER.fetchFrom(params);
            // Service installer tool will be installed in launchers directory
            serviceInstaller = new InstallableFile(
                    serviceInstaller.srcPath().toAbsolutePath().normalize(),
                    installedAppImage.launchersDirectory().resolve(
                            serviceInstaller.installPath()));
        }

        programMenuFolderName = MENU_GROUP.fetchFrom(params);

        initFileAssociations(params);
    }

    @Override
    void addFilesToConfigRoot() throws IOException {
        removeFolderItems = new HashMap<>();
        defaultedMimes = new HashSet<>();
        super.addFilesToConfigRoot();
    }

    @Override
    List<String> getLoggableWixFeatures() {
        if (isWithWix36Features()) {
            return List.of(MessageFormat.format(I18N.getString("message.use-wix36-features"),
                    getWixVersion()));
        } else {
            return List.of();
        }
    }

    @Override
    protected Collection<XmlConsumer> getFragmentWriters() {
        return List.of(
                xml -> {
                    addFaComponentGroup(xml);

                    addShortcutComponentGroup(xml);

                    addFilesComponentGroup(xml);

                    for (var shortcutFolder : shortcutFolders) {
                        xml.writeStartElement("Property");
                        xml.writeAttribute("Id", shortcutFolder.property);
                        xml.writeAttribute("Value", "1");
                        xml.writeEndElement();
                    }
                },
                this::addIcons
        );
    }

    private void normalizeFileAssociation(FileAssociation fa) {
        fa.launcherPath = addExeSuffixToPath(
                installedAppImage.launchersDirectory().resolve(fa.launcherPath));

        if (fa.iconPath != null && !Files.exists(fa.iconPath)) {
            fa.iconPath = null;
        }

        if (fa.iconPath != null) {
            fa.iconPath = fa.iconPath.toAbsolutePath();
        }

        // Filter out empty extensions.
        fa.extensions = fa.extensions.stream().filter(Predicate.not(
                String::isEmpty)).toList();
    }

    private static Path addExeSuffixToPath(Path path) {
        return PathUtils.addSuffix(path, ".exe");
    }

    private Path getInstalledFaIcoPath(FileAssociation fa) {
        String fname = String.format("fa_%s.ico", String.join("_", fa.extensions));
        return installedAppImage.destktopIntegrationDirectory().resolve(fname);
    }

    private void initFileAssociations(Map<String, ? super Object> params) {
        associations = FileAssociation.fetchFrom(params).stream()
                .peek(this::normalizeFileAssociation)
                // Filter out file associations without extensions.
                .filter(fa -> !fa.extensions.isEmpty())
                .toList();

        associations.stream().filter(fa -> fa.iconPath != null).forEach(fa -> {
            // Need to add fa icon in the image.
            Object key = new Object();
            appImage.pathGroup().setPath(key, fa.iconPath);
            installedAppImage.pathGroup().setPath(key, getInstalledFaIcoPath(fa));
        });
    }

    private static UUID createNameUUID(String str) {
        return UUID.nameUUIDFromBytes(str.getBytes(StandardCharsets.UTF_8));
    }

    private static UUID createNameUUID(Path path, String role) {
        if (path.isAbsolute() || !ROOT_DIRS.contains(path.getName(0))) {
            throw throwInvalidPathException(path);
        }
        // Paths are case insensitive on Windows
        String keyPath = path.toString().toLowerCase();
        if (role != null) {
            keyPath = role + "@" + keyPath;
        }
        return createNameUUID(keyPath);
    }

    /**
     * Value for Id attribute of various WiX elements.
     */
    enum Id {
        File,
        Folder("dir"),
        Shortcut,
        ProgId,
        Icon,
        CreateFolder("mkdir"),
        RemoveFolder("rm");

        Id() {
            this.prefix = name().toLowerCase();
        }

        Id(String prefix) {
            this.prefix = prefix;
        }

        String of(Path path) {
            if (this == Folder && KNOWN_DIRS.contains(path)) {
                return IOUtils.getFileName(path).toString();
            }

            String result = of(path, prefix, name());

            if (this == Icon) {
                // Icon id constructed from UUID value is too long and triggers
                // CNDL1000 warning, so use Java hash code instead.
                result = String.format("%s%d", prefix, result.hashCode()).replace(
                        "-", "_");
            }

            return result;
        }

        private static String of(Path path, String prefix, String role) {
            Objects.requireNonNull(role);
            Objects.requireNonNull(prefix);
            return String.format("%s%s", prefix,
                    createNameUUID(path, role).toString().replace("-", ""));
        }

        static String of(Path path, String prefix) {
            return of(path, prefix, prefix);
        }

        private final String prefix;
    }

    enum Component {
        File(cfg().file()),
        Shortcut(cfg().file().withRegistryKeyPath()),
        ProgId(cfg().file().withRegistryKeyPath()),
        CreateFolder(cfg().withRegistryKeyPath()),
        RemoveFolder(cfg().withRegistryKeyPath());

        Component() {
            this.cfg = cfg();
            this.id = Id.valueOf(name());
        }

        Component(Config cfg) {
            this.cfg = cfg;
            this.id = Id.valueOf(name());
        }

        UUID guidOf(Path path) {
            return createNameUUID(path, name());
        }

        String idOf(Path path) {
            return id.of(path);
        }

        boolean isRegistryKeyPath() {
            return cfg.withRegistryKeyPath;
        }

        boolean isFile() {
            return cfg.isFile;
        }

        static void startElement(WixToolsetType wixType, XMLStreamWriter xml, String componentId,
                String componentGuid) throws XMLStreamException, IOException {
            xml.writeStartElement("Component");
            switch (wixType) {
                case Wix3 -> {
                    xml.writeAttribute("Win64", is64Bit() ? "yes" : "no");
                    xml.writeAttribute("Guid", componentGuid);
                }
                case Wix4 -> {
                    xml.writeAttribute("Bitness", is64Bit() ? "always64" : "always32");
                    if (!componentGuid.equals("*")) {
                        xml.writeAttribute("Guid", componentGuid);
                    }
                }
                default -> {
                    throw new IllegalArgumentException();
                }
            }
            xml.writeAttribute("Id", componentId);
        }

        private static final class Config {
            Config withRegistryKeyPath() {
                withRegistryKeyPath = true;
                return this;
            }

            Config file() {
                isFile = true;
                return this;
            }

            private boolean isFile;
            private boolean withRegistryKeyPath;
        }

        private static Config cfg() {
            return new Config();
        }

        private final Config cfg;
        private final Id id;
    };

    private static void addComponentGroup(XMLStreamWriter xml, String id,
            List<String> componentIds) throws XMLStreamException, IOException {
        xml.writeStartElement("ComponentGroup");
        xml.writeAttribute("Id", id);
        componentIds = componentIds.stream().filter(Objects::nonNull).collect(
                Collectors.toList());
        for (var componentId : componentIds) {
            xml.writeStartElement("ComponentRef");
            xml.writeAttribute("Id", componentId);
            xml.writeEndElement();
        }
        xml.writeEndElement();
    }

    private String addComponent(XMLStreamWriter xml, Path path,
            Component role, XmlConsumer xmlConsumer) throws XMLStreamException,
            IOException {

        final Path directoryRefPath;
        if (role.isFile()) {
            directoryRefPath = path.getParent();
        } else {
            directoryRefPath = path;
        }

        startDirectoryElement(xml, "DirectoryRef", directoryRefPath);

        final String componentId = "c" + role.idOf(path);
        Component.startElement(getWixType(), xml, componentId, String.format(
                "{%s}", role.guidOf(path)));

        if (role == Component.Shortcut) {
            String property = shortcutFolders.stream().filter(shortcutFolder -> {
                return path.startsWith(shortcutFolder.root);
            }).map(shortcutFolder -> {
                return shortcutFolder.property;
            }).findFirst().get();
            switch (getWixType()) {
                case Wix3 -> {
                    xml.writeStartElement("Condition");
                    xml.writeCharacters(property);
                    xml.writeEndElement();
                }
                case Wix4 -> {
                    xml.writeAttribute("Condition", property);
                }
                default -> {
                    throw new IllegalArgumentException();
                }
            }
        }

        boolean isRegistryKeyPath = !systemWide || role.isRegistryKeyPath();
        if (isRegistryKeyPath) {
            addRegistryKeyPath(xml, directoryRefPath);
            if ((role.isFile() || (role == Component.CreateFolder
                    && !systemWide)) && !SYSTEM_DIRS.contains(directoryRefPath)) {
                xml.writeStartElement("RemoveFolder");
                int counter = Optional.ofNullable(removeFolderItems.get(
                        directoryRefPath)).orElse(Integer.valueOf(0)).intValue() + 1;
                removeFolderItems.put(directoryRefPath, counter);
                xml.writeAttribute("Id", String.format("%s_%d", Id.RemoveFolder.of(
                        directoryRefPath), counter));
                xml.writeAttribute("On", "uninstall");
                xml.writeEndElement();
            }
        }

        xml.writeStartElement(role.name());
        if (role != Component.CreateFolder) {
            xml.writeAttribute("Id", role.idOf(path));
        }

        if (!isRegistryKeyPath) {
            xml.writeAttribute("KeyPath", "yes");
        }

        xmlConsumer.accept(xml);
        xml.writeEndElement();

        if (role == Component.File && serviceInstaller != null && path.equals(
                serviceInstaller.installPath())) {
            for (var launcherAsService : launchersAsServices) {
                launcherAsService.writeServiceInstall(xml);
            }
        }

        xml.writeEndElement(); // <Component>
        xml.writeEndElement(); // <DirectoryRef>

        return componentId;
    }

    private void addFaComponentGroup(XMLStreamWriter xml)
            throws XMLStreamException, IOException {

        List<String> componentIds = new ArrayList<>();
        for (var fa : associations) {
            componentIds.addAll(addFaComponents(xml, fa));
        }
        addComponentGroup(xml, "FileAssociations", componentIds);
    }

    private void addShortcutComponentGroup(XMLStreamWriter xml) throws
            XMLStreamException, IOException {
        List<String> componentIds = new ArrayList<>();
        Set<Path> defineShortcutFolders = new HashSet<>();
        for (var launcher : launchers) {
            for (var folder : shortcutFolders) {
                Path launcherPath = addExeSuffixToPath(installedAppImage
                        .launchersDirectory().resolve(launcher.getName()));

                if ((launcher.isMenu() &&
                        (folder.equals(ShortcutsFolder.ProgramMenu))) ||
                    (launcher.isShortcut() &&
                        (folder.equals(ShortcutsFolder.Desktop)))) {

                    String componentId = addShortcutComponent(xml, launcherPath,
                            folder);

                    if (componentId != null) {
                        Path folderPath = folder.getPath(this);
                        boolean defineFolder;
                        switch (getWixType()) {
                            case Wix3 ->
                                defineFolder = true;
                            case Wix4 ->
                                defineFolder = !SYSTEM_DIRS.contains(folderPath);
                            default ->
                                throw new IllegalArgumentException();
                        }
                        if (defineFolder) {
                            defineShortcutFolders.add(folderPath);
                        }
                        componentIds.add(componentId);
                    }
                }
            }
        }

        for (var folderPath : defineShortcutFolders) {
            componentIds.addAll(addRootBranch(xml, folderPath));
        }

        addComponentGroup(xml, "Shortcuts", componentIds);
    }

    private String addShortcutComponent(XMLStreamWriter xml, Path launcherPath,
            ShortcutsFolder folder) throws XMLStreamException, IOException {
        Objects.requireNonNull(folder);

        if (!INSTALLDIR.equals(launcherPath.getName(0))) {
            throw throwInvalidPathException(launcherPath);
        }

        String launcherBasename = PathUtils.replaceSuffix(
                IOUtils.getFileName(launcherPath), "").toString();

        Path shortcutPath = folder.getPath(this).resolve(launcherBasename);
        return addComponent(xml, shortcutPath, Component.Shortcut, unused -> {
            xml.writeAttribute("Name", launcherBasename);
            xml.writeAttribute("WorkingDirectory", INSTALLDIR.toString());
            xml.writeAttribute("Advertise", "no");
            xml.writeAttribute("Target", String.format("[#%s]",
                    Component.File.idOf(launcherPath)));
        });
    }

    private List<String> addFaComponents(XMLStreamWriter xml,
            FileAssociation fa) throws XMLStreamException, IOException {
        List<String> components = new ArrayList<>();
        for (var extension: fa.extensions) {
            Path path = INSTALLDIR.resolve(String.format("%s_%s", extension,
                    fa.launcherPath.getFileName()));
            components.add(addComponent(xml, path, Component.ProgId, unused -> {
                xml.writeAttribute("Description", fa.description);

                if (fa.iconPath != null) {
                    xml.writeAttribute("Icon", Id.File.of(getInstalledFaIcoPath(
                            fa)));
                    xml.writeAttribute("IconIndex", "0");
                }

                xml.writeStartElement("Extension");
                xml.writeAttribute("Id", extension);
                xml.writeAttribute("Advertise", "no");

                var mimeIt = fa.mimeTypes.iterator();
                if (mimeIt.hasNext()) {
                    String mime = mimeIt.next();
                    xml.writeAttribute("ContentType", mime);

                    if (!defaultedMimes.contains(mime)) {
                        xml.writeStartElement("MIME");
                        xml.writeAttribute("ContentType", mime);
                        xml.writeAttribute("Default", "yes");
                        xml.writeEndElement();
                        defaultedMimes.add(mime);
                    }
                }

                xml.writeStartElement("Verb");
                xml.writeAttribute("Id", "open");
                xml.writeAttribute("Command", "!(loc.ContextMenuCommandLabel)");
                xml.writeAttribute("Argument", "\"%1\" %*");
                xml.writeAttribute("TargetFile", Id.File.of(fa.launcherPath));
                xml.writeEndElement(); // <Verb>

                xml.writeEndElement(); // <Extension>
            }));
        }

        return components;
    }

    private List<String> addRootBranch(XMLStreamWriter xml, Path path)
            throws XMLStreamException, IOException {
        if (!ROOT_DIRS.contains(path.getName(0))) {
            throw throwInvalidPathException(path);
        }

        boolean sysDir = true;
        int levels;
        var dirIt = path.iterator();

        if (getWixType() != WixToolsetType.Wix3 && TARGETDIR.equals(path.getName(0))) {
            levels = 0;
            dirIt.next();
        } else {
            levels = 1;
            xml.writeStartElement("DirectoryRef");
            xml.writeAttribute("Id", dirIt.next().toString());
        }

        path = path.getName(0);
        while (dirIt.hasNext()) {
            levels++;
            Path name = dirIt.next();
            path = path.resolve(name);

            if (sysDir && !SYSTEM_DIRS.contains(path)) {
                sysDir = false;
            }

            startDirectoryElement(xml, "Directory", path);
            if (!sysDir) {
                xml.writeAttribute("Name", path.getFileName().toString());
            }
        }

        while (0 != levels--) {
            xml.writeEndElement();
        }

        return List.of();
    }

    private void startDirectoryElement(XMLStreamWriter xml, String wix3ElementName, Path path) throws XMLStreamException {
        final String elementName;
        switch (getWixType()) {
            case Wix3 -> {
                elementName = wix3ElementName;
            }
            case Wix4 -> {
                if (SYSTEM_DIRS.contains(path)) {
                    elementName = "StandardDirectory";
                } else {
                    elementName = wix3ElementName;
                }
            }
            default -> {
                throw new IllegalArgumentException();
            }

        }

        final String directoryId;
        if (path.equals(installDir)) {
            directoryId = INSTALLDIR.toString();
        } else {
            directoryId = Id.Folder.of(path);
        }

        xml.writeStartElement(elementName);
        xml.writeAttribute("Id", directoryId);
    }

    private String addRemoveDirectoryComponent(XMLStreamWriter xml, Path path)
            throws XMLStreamException, IOException {
        return addComponent(xml, path, Component.RemoveFolder,
                unused -> xml.writeAttribute("On", "uninstall"));
    }

    private List<String> addDirectoryHierarchy(XMLStreamWriter xml)
            throws XMLStreamException, IOException {

        Set<Path> allDirs = new HashSet<>();
        Set<Path> emptyDirs = new HashSet<>();
        appImage.transform(installedAppImage, new PathGroup.TransformHandler() {
            @Override
            public void copyFile(Path src, Path dst) throws IOException {
                Path dir = dst.getParent();
                createDirectory(dir);
                emptyDirs.remove(dir);
            }

            @Override
            public void createDirectory(final Path dir) throws IOException {
                if (!allDirs.contains(dir)) {
                    emptyDirs.add(dir);
                }

                Path it = dir;
                while (it != null && allDirs.add(it)) {
                    it = it.getParent();
                }

                it = dir;
                while ((it = it.getParent()) != null && emptyDirs.remove(it));
            }
        });

        List<String> componentIds = new ArrayList<>();
        for (var dir : emptyDirs) {
            componentIds.add(addComponent(xml, dir, Component.CreateFolder,
                    unused -> {}));
        }

        if (!systemWide) {
            // Per-user install requires <RemoveFolder> component in every
            // directory.
            for (var dir : allDirs.stream()
                    .filter(Predicate.not(emptyDirs::contains))
                    .filter(Predicate.not(removeFolderItems::containsKey))
                    .toList()) {
                componentIds.add(addRemoveDirectoryComponent(xml, dir));
            }
        }

        allDirs.remove(INSTALLDIR);
        for (var dir : allDirs) {
            xml.writeStartElement("DirectoryRef");
            xml.writeAttribute("Id", Id.Folder.of(dir.getParent()));
            xml.writeStartElement("Directory");
            xml.writeAttribute("Id", Id.Folder.of(dir));
            xml.writeAttribute("Name", IOUtils.getFileName(dir).toString());
            xml.writeEndElement();
            xml.writeEndElement();
        }

        componentIds.addAll(addRootBranch(xml, installDir));

        return componentIds;
    }

    private void addFilesComponentGroup(XMLStreamWriter xml)
            throws XMLStreamException, IOException {

        List<Map.Entry<Path, Path>> files = new ArrayList<>();
        appImage.transform(installedAppImage, new PathGroup.TransformHandler() {
            @Override
            public void copyFile(Path src, Path dst) throws IOException {
                files.add(Map.entry(src, dst));
            }

            @Override
            public void createDirectory(final Path dir) throws IOException {
            }
        });

        if (serviceInstaller != null) {
            files.add(Map.entry(serviceInstaller.srcPath(),
                    serviceInstaller.installPath()));
        }

        List<String> componentIds = new ArrayList<>();
        for (var file : files) {
            Path src = file.getKey();
            Path dst = file.getValue();

            componentIds.add(addComponent(xml, dst, Component.File, unused -> {
                xml.writeAttribute("Source", src.normalize().toString());
                Path name = dst.getFileName();
                if (!name.equals(src.getFileName())) {
                    xml.writeAttribute("Name", name.toString());
                }
            }));
        }

        componentIds.addAll(addDirectoryHierarchy(xml));

        componentIds.add(addDirectoryCleaner(xml, INSTALLDIR));

        componentIds.addAll(addServiceConfigs(xml));

        addComponentGroup(xml, "Files", componentIds);
    }

    private List<String> addServiceConfigs(XMLStreamWriter xml) throws
            XMLStreamException, IOException {
        if (launchersAsServices.isEmpty()) {
            return List.of();
        }

        try {
            var buffer = new DOMResult(XmlUtils.initDocumentBuilder().newDocument());
            var bufferWriter = XMLOutputFactory.newInstance().createXMLStreamWriter(
                    buffer);

            bufferWriter.writeStartDocument();
            bufferWriter.writeStartElement("Include");
            for (var launcherAsService : launchersAsServices) {
                launcherAsService.writeServiceConfig(xml);
                launcherAsService.writeServiceConfig(bufferWriter);
            }
            bufferWriter.writeEndElement();
            bufferWriter.writeEndDocument();
            bufferWriter.flush();
            bufferWriter.close();

            XPath xPath = XPathFactory.newInstance().newXPath();

            NodeList nodes = (NodeList) xPath.evaluate("/Include/descendant::Component/@Id",
                    buffer.getNode(), XPathConstants.NODESET);

            List<String> componentIds = new ArrayList<>();
            for (int i = 0; i != nodes.getLength(); i++) {
                var node = nodes.item(i);
                componentIds.add(node.getNodeValue());
            }
            return componentIds;
        } catch (XMLStreamException ex) {
            throw new IOException(ex);
        } catch (XPathExpressionException ex) {
            // Should never happen
            throw new RuntimeException(ex);
        }
    }

    private void addIcons(XMLStreamWriter xml) throws
            XMLStreamException, IOException {

        PathGroup srcPathGroup = appImage.pathGroup();
        PathGroup dstPathGroup = installedAppImage.pathGroup();

        // Build list of copy operations for all .ico files in application image
        List<Map.Entry<Path, Path>> icoFiles = new ArrayList<>();
        srcPathGroup.transform(dstPathGroup, new PathGroup.TransformHandler() {
            @Override
            public void copyFile(Path src, Path dst) throws IOException {
                if (IOUtils.getFileName(src).toString().endsWith(".ico")) {
                    icoFiles.add(Map.entry(src, dst));
                }
            }

            @Override
            public void createDirectory(Path dst) throws IOException {
            }
        });

        for (var icoFile : icoFiles) {
            xml.writeStartElement("Icon");
            xml.writeAttribute("Id", Id.Icon.of(icoFile.getValue()));
            xml.writeAttribute("SourceFile", icoFile.getKey().toString());
            xml.writeEndElement();
        }
    }

    private void addRegistryKeyPath(XMLStreamWriter xml, Path path) throws
            XMLStreamException, IOException {
        addRegistryKeyPath(xml, path, () -> "ProductCode", () -> "[ProductCode]");
    }

    private void addRegistryKeyPath(XMLStreamWriter xml, Path path,
            Supplier<String> nameAttr, Supplier<String> valueAttr) throws
            XMLStreamException, IOException {

        String regRoot = USER_PROFILE_DIRS.stream().anyMatch(path::startsWith)
                || !systemWide ? "HKCU" : "HKLM";

        xml.writeStartElement("RegistryKey");
        xml.writeAttribute("Root", regRoot);
        xml.writeAttribute("Key", registryKeyPath);
        if (!isWithWix36Features()) {
            xml.writeAttribute("Action", "createAndRemoveOnUninstall");
        }
        xml.writeStartElement("RegistryValue");
        xml.writeAttribute("Type", "string");
        xml.writeAttribute("KeyPath", "yes");
        xml.writeAttribute("Name", nameAttr.get());
        xml.writeAttribute("Value", valueAttr.get());
        xml.writeEndElement(); // <RegistryValue>
        xml.writeEndElement(); // <RegistryKey>
    }

    private String addDirectoryCleaner(XMLStreamWriter xml, Path path) throws
            XMLStreamException, IOException {
        if (!isWithWix36Features()) {
            return null;
        }

        // rm -rf
        final String baseId = Id.of(path, "rm_rf");
        final String propertyId = baseId.toUpperCase();
        final String componentId = ("c" + baseId);

        xml.writeStartElement("Property");
        xml.writeAttribute("Id", propertyId);
        xml.writeStartElement("RegistrySearch");
        xml.writeAttribute("Id", Id.of(path, "regsearch"));
        xml.writeAttribute("Root", systemWide ? "HKLM" : "HKCU");
        xml.writeAttribute("Key", registryKeyPath);
        xml.writeAttribute("Type", "raw");
        xml.writeAttribute("Name", propertyId);
        xml.writeEndElement(); // <RegistrySearch>
        xml.writeEndElement(); // <Property>

        xml.writeStartElement("DirectoryRef");
        xml.writeAttribute("Id", INSTALLDIR.toString());
        Component.startElement(getWixType(), xml, componentId, "*");

        addRegistryKeyPath(xml, INSTALLDIR, () -> propertyId, () -> {
            return toWixPath(path);
        });

        xml.writeStartElement(getWixNamespaces().get(WixNamespace.Util),
                "RemoveFolderEx");
        xml.writeAttribute("On", "uninstall");
        xml.writeAttribute("Property", propertyId);
        xml.writeEndElement(); // <RemoveFolderEx>
        xml.writeEndElement(); // <Component>
        xml.writeEndElement(); // <DirectoryRef>

        return componentId;
    }

    private boolean isWithWix36Features() {
        return DottedVersion.compareComponents(getWixVersion(), DottedVersion.greedy("3.6")) >= 0;
    }

    // Does the following conversions:
    //  INSTALLDIR -> [INSTALLDIR]
    //  TARGETDIR/ProgramFiles64Folder/foo/bar -> [ProgramFiles64Folder]foo/bar
    private static String toWixPath(Path path) {
        final Path rootDir = KNOWN_DIRS.stream()
                .sorted(Comparator.comparing(Path::getNameCount).reversed())
                .filter(path::startsWith)
                .findFirst().get();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[%s]", rootDir.getFileName().toString()));
        sb.append(rootDir.relativize(path).toString());
        return sb.toString();
    }

    private static IllegalArgumentException throwInvalidPathException(Path v) {
        throw new IllegalArgumentException(String.format("Invalid path [%s]", v));
    }

    enum ShortcutsFolder {
        ProgramMenu(PROGRAM_MENU_PATH, Arguments.CLIOptions.WIN_MENU_HINT,
                "JP_INSTALL_STARTMENU_SHORTCUT", "JpStartMenuShortcutPrompt"),
        Desktop(DESKTOP_PATH, Arguments.CLIOptions.WIN_SHORTCUT_HINT,
                "JP_INSTALL_DESKTOP_SHORTCUT", "JpDesktopShortcutPrompt");

        private ShortcutsFolder(Path root, Arguments.CLIOptions cliOption,
                String property, String wixVariableName) {
            this.root = root;
            this.bundlerParam = new StandardBundlerParam<>(
                    cliOption.getId(),
                    Boolean.class,
                    params -> false,
                    // valueOf(null) is false,
                    // and we actually do want null in some cases
                    (s, p) -> (s == null || "null".equalsIgnoreCase(s)) ? false : Boolean.valueOf(s)
            );
            this.wixVariableName = wixVariableName;
            this.property = property;
        }

        Path getPath(WixAppImageFragmentBuilder outer) {
            if (this == ProgramMenu) {
                return root.resolve(outer.programMenuFolderName);
            }
            return root;
        }

        boolean requested(Map<String, ? super Object> params) {
            return bundlerParam.fetchFrom(params);
        }

        String getWixVariableName() {
            return wixVariableName;
        }

        private final Path root;
        private final String property;
        private final String wixVariableName;
        private final StandardBundlerParam<Boolean> bundlerParam;
    }

    private boolean systemWide;

    private String registryKeyPath;

    private Path installDir;

    private String programMenuFolderName;

    private List<FileAssociation> associations;

    private Set<ShortcutsFolder> shortcutFolders;

    private List<LauncherInfo> launchers;

    private List<WixLauncherAsService> launchersAsServices;

    private InstallableFile serviceInstaller;

    private ApplicationLayout appImage;
    private ApplicationLayout installedAppImage;

    private Map<Path, Integer> removeFolderItems;
    private Set<String> defaultedMimes;

    private static final Path TARGETDIR = Path.of("TARGETDIR");

    private static final Path INSTALLDIR = Path.of("INSTALLDIR");

    private static final Set<Path> ROOT_DIRS = Set.of(INSTALLDIR, TARGETDIR);

    private static final Path PROGRAM_MENU_PATH = TARGETDIR.resolve("ProgramMenuFolder");

    private static final Path DESKTOP_PATH = TARGETDIR.resolve("DesktopFolder");

    private static final Path PROGRAM_FILES = TARGETDIR.resolve(
            is64Bit() ? "ProgramFiles64Folder" : "ProgramFilesFolder");

    private static final Path LOCAL_PROGRAM_FILES = TARGETDIR.resolve("LocalAppDataFolder");

    private static final Set<Path> SYSTEM_DIRS = Set.of(TARGETDIR,
            PROGRAM_MENU_PATH, DESKTOP_PATH, PROGRAM_FILES, LOCAL_PROGRAM_FILES);

    private static final Set<Path> KNOWN_DIRS = Stream.of(Set.of(INSTALLDIR),
            SYSTEM_DIRS).flatMap(Set::stream).collect(
            Collectors.toUnmodifiableSet());

    private static final Set<Path> USER_PROFILE_DIRS = Set.of(LOCAL_PROGRAM_FILES,
            PROGRAM_MENU_PATH, DESKTOP_PATH);

    private static final StandardBundlerParam<String> MENU_GROUP =
            new StandardBundlerParam<>(
                    Arguments.CLIOptions.WIN_MENU_GROUP.getId(),
                    String.class,
                    params -> I18N.getString("param.menu-group.default"),
                    (s, p) -> s
            );

    private static final BundlerParamInfo<String> WINDOWS_INSTALL_DIR =
            new StandardBundlerParam<>(
            "windows-install-dir",
            String.class,
            params -> {
                 String dir = INSTALL_DIR.fetchFrom(params);
                 if (dir != null) {
                     if (dir.contains(":") || dir.contains("..")) {
                         Log.error(MessageFormat.format(I18N.getString(
                                "message.invalid.install.dir"), dir,
                                APP_NAME.fetchFrom(params)));
                     } else {
                        if (dir.startsWith("\\")) {
                             dir = dir.substring(1);
                        }
                        if (dir.endsWith("\\")) {
                             dir = dir.substring(0, dir.length() - 1);
                        }
                        return dir;
                     }
                 }
                 return APP_NAME.fetchFrom(params); // Default to app name
             },
            (s, p) -> s
    );
}
