// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.cloud.hypervisor.kvm.storage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.cloudstack.utils.qemu.QemuImageOptions;
import org.apache.cloudstack.utils.qemu.QemuImg;
import org.apache.cloudstack.utils.qemu.QemuImg.PhysicalDiskFormat;
import org.apache.cloudstack.utils.qemu.QemuImgException;
import org.apache.cloudstack.utils.qemu.QemuImgFile;
import org.apache.cloudstack.utils.qemu.QemuObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.libvirt.Connect;
import org.libvirt.StoragePool;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.hypervisor.kvm.resource.LibvirtConnection;
import com.cloud.hypervisor.kvm.resource.LibvirtStoragePoolDef;
import com.cloud.storage.Storage;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;

@RunWith(MockitoJUnitRunner.class)
public class LibvirtStorageAdaptorTest {

    private MockedStatic<LibvirtConnection> libvirtConnectionMockedStatic;

    private AutoCloseable closeable;

    @Mock
    LibvirtStoragePool mockPool;

    MockedStatic<Script> mockScript;

    @Spy
    static LibvirtStorageAdaptor libvirtStorageAdaptor = new LibvirtStorageAdaptor(null);

    @Before
    public void initMocks() {
        closeable = MockitoAnnotations.openMocks(this);
        libvirtConnectionMockedStatic = Mockito.mockStatic(LibvirtConnection.class);
        Mockito.reset(mockPool);
        mockScript = Mockito.mockStatic(Script.class);
    }

    @After
    public void tearDown() throws Exception {
        libvirtConnectionMockedStatic.close();
        mockScript.close();
        closeable.close();
    }

    @Test(expected = CloudRuntimeException.class)
    public void testCreateStoragePoolWithNFSMountOpts() throws Exception {
        LibvirtStoragePoolDef.PoolType type = LibvirtStoragePoolDef.PoolType.NETFS;
        String name = "Primary1";
        String uuid = String.valueOf(UUID.randomUUID());
        String host = "127.0.0.1";
        String dir  = "/export/primary";
        String targetPath = "/mnt/" + uuid;

        String poolXml = "<pool type='" + type.toString() + "' xmlns:fs='http://libvirt.org/schemas/storagepool/fs/1.0'>\n" +
                "<name>" +name + "</name>\n<uuid>" + uuid + "</uuid>\n" +
                "<source>\n<host name='" + host + "'/>\n<dir path='" + dir + "'/>\n</source>\n<target>\n" +
                "<path>" + targetPath + "</path>\n</target>\n" +
                "<fs:mount_opts>\n<fs:option name='vers=4.1'/>\n<fs:option name='nconnect=4'/>\n</fs:mount_opts>\n</pool>\n";

        Connect conn =  Mockito.mock(Connect.class);
        StoragePool sp = Mockito.mock(StoragePool.class);
        Mockito.when(LibvirtConnection.getConnection()).thenReturn(conn);
        Mockito.when(conn.storagePoolLookupByUUIDString(uuid)).thenReturn(sp);
        Mockito.when(sp.isActive()).thenReturn(1);
        Mockito.when(sp.getXMLDesc(0)).thenReturn(poolXml);
        Mockito.when(Script.runSimpleBashScriptForExitValue(anyString())).thenReturn(-1);

        Map<String, String> details = new HashMap<>();
        details.put("nfsmountopts", "vers=4.1, nconnect=4");
        KVMStoragePool pool = libvirtStorageAdaptor.createStoragePool(uuid, null, 0, dir, null, Storage.StoragePoolType.NetworkFilesystem, details, true);
    }

    @Test
    public void testUpdateLocalPoolIops_IgnoredForNonFilesystemType() {
        Mockito.when(mockPool.getType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);

        libvirtStorageAdaptor.updateLocalPoolIops(mockPool);

        libvirtStorageAdaptor.updateLocalPoolIops(mockPool);
    }

    @Test
    public void testUpdateLocalPoolIops_IgnoredForBlankLocalPath() {
        Mockito.when(mockPool.getType()).thenReturn(Storage.StoragePoolType.Filesystem);
        Mockito.when(mockPool.getLocalPath()).thenReturn("");

        Mockito.verify(mockPool, never()).getLocalPath();
        libvirtStorageAdaptor.updateLocalPoolIops(mockPool);

        Mockito.verify(mockPool, never()).setUsedIops(anyLong());
    }

    @Test
    public void testUpdateLocalPoolIops_NoDevice() {
        Mockito.when(mockPool.getType()).thenReturn(Storage.StoragePoolType.Filesystem);
        Mockito.when(mockPool.getLocalPath()).thenReturn("/mock/path");
        Mockito.when(mockPool.getName()).thenReturn("mockPool");
        Mockito.when(Script.executePipedCommands(anyList(), Mockito.eq(1000L))).thenReturn(new Pair<>(0, "\n"));

        libvirtStorageAdaptor.updateLocalPoolIops(mockPool);

        Mockito.verify(mockPool, never()).setUsedIops(anyLong());
    }

    @Test
    public void testUpdateLocalPoolIops_SuccessfulUpdate() {
        Mockito.when(mockPool.getType()).thenReturn(Storage.StoragePoolType.Filesystem);
        Mockito.when(mockPool.getLocalPath()).thenReturn("/mock/path");
        Mockito.when(mockPool.getName()).thenReturn("mockPool");
        Mockito.when(Script.executePipedCommands(anyList(), Mockito.eq(1000L))).thenReturn(new Pair<>(0, "sda\n"));
        Mockito.when(Script.executePipedCommands(anyList(), Mockito.eq(10000L))).thenReturn(new Pair<>(0, "42\n"));

        libvirtStorageAdaptor.updateLocalPoolIops(mockPool);

        Mockito.verify(mockPool).setUsedIops(42L);
    }

    @Test
    public void testUpdateLocalPoolIops_HandlesNumberFormatException() {
        Mockito.when(mockPool.getType()).thenReturn(Storage.StoragePoolType.Filesystem);
        Mockito.when(mockPool.getLocalPath()).thenReturn("/mock/path");
        Mockito.when(mockPool.getName()).thenReturn("mockPool");
        Mockito.when(Script.executePipedCommands(anyList(), Mockito.eq(1000L))).thenReturn(new Pair<>(0, "sda\n"));
        Mockito.when(Script.executePipedCommands(anyList(), Mockito.eq(10000L)))
                .thenReturn(new Pair<>(0, "invalid_number"));

        libvirtStorageAdaptor.updateLocalPoolIops(mockPool);

        Mockito.verify(mockPool, never()).setUsedIops(anyLong());
    }

    @Test
    public void testUpdateLocalPoolIops_NullResultFromScript() {
        Mockito.when(mockPool.getType()).thenReturn(Storage.StoragePoolType.Filesystem);
        Mockito.when(mockPool.getLocalPath()).thenReturn("/mock/path");
        Mockito.when(mockPool.getName()).thenReturn("mockPool");
        Mockito.when(Script.executePipedCommands(anyList(), Mockito.eq(1000L))).thenReturn(new Pair<>(0, "sda\n"));
        Mockito.when(Script.executePipedCommands(anyList(), Mockito.eq(10000L)))
                .thenReturn(new Pair<>(0, null));

        libvirtStorageAdaptor.updateLocalPoolIops(mockPool);

        Mockito.verify(mockPool, never()).setUsedIops(anyLong());
    }

    private static final byte[] SRC_PASSPHRASE = "source-passphrase".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DST_PASSPHRASE = "destination-passphrase".getBytes(StandardCharsets.UTF_8);

    private static final String SRC_PATH = "/mnt/src-pool/0f1e2d3c-source.qcow2";
    private static final String DEST_NAME = "4a5b6c7d-dest.qcow2";
    private static final String DEST_PATH = "/mnt/dest-pool/" + DEST_NAME;
    private static final long VIRTUAL_SIZE = 2048L;

    private final List<Object[]> convertCalls = new ArrayList<>();

    private MockedConstruction<QemuImg> mockQemuImg(Map<String, String> info, QemuImgException convertFailure) {
        return Mockito.mockConstruction(QemuImg.class, (mock, context) -> {
            Mockito.when(mock.info(any(QemuImgFile.class))).thenReturn(info);
            Mockito.doAnswer(invocation -> {
                convertCalls.add(invocation.getArguments());
                if (convertFailure != null) {
                    throw convertFailure;
                }
                return null;
            }).when(mock).convert(any(), any(), any(), any(), any(), any(), Mockito.anyBoolean(), Mockito.anyBoolean());
        });
    }

    private Map<String, String> qemuInfo(String backingFile) {
        Map<String, String> info = new HashMap<>();
        info.put(QemuImg.VIRTUAL_SIZE, String.valueOf(VIRTUAL_SIZE));
        if (backingFile != null) {
            info.put(QemuImg.BACKING_FILE, backingFile);
        }
        return info;
    }

    private KVMPhysicalDisk sourceDisk(Storage.StoragePoolType srcPoolType, PhysicalDiskFormat format) {
        KVMStoragePool srcPool = Mockito.mock(KVMStoragePool.class);
        Mockito.lenient().when(srcPool.getType()).thenReturn(srcPoolType);
        KVMPhysicalDisk disk = new KVMPhysicalDisk(SRC_PATH, "0f1e2d3c-source.qcow2", srcPool);
        disk.setFormat(format);
        disk.setSize(1024L);
        disk.setVirtualSize(VIRTUAL_SIZE);
        return disk;
    }

    private KVMStoragePool destinationPool(Storage.StoragePoolType destPoolType) {
        KVMPhysicalDisk newDisk = new KVMPhysicalDisk(DEST_PATH, DEST_NAME, null);
        newDisk.setFormat(PhysicalDiskFormat.QCOW2);
        KVMStoragePool destPool = Mockito.mock(KVMStoragePool.class);
        Mockito.lenient().when(destPool.getType()).thenReturn(destPoolType);
        Mockito.lenient().when(destPool.createPhysicalDisk(anyString(), any(Storage.ProvisioningType.class), anyLong(), any()))
                .thenReturn(newDisk);
        Mockito.lenient().when(destPool.getSourceDir()).thenReturn("/mnt/dest-pool");
        return destPool;
    }

    private String secretFlag(List<QemuObject> qemuObjects, String secretId) {
        for (QemuObject qemuObject : qemuObjects) {
            String flag = qemuObject.toCommandFlag()[1];
            if (flag.endsWith("id=" + secretId)) {
                return flag;
            }
        }
        return null;
    }

    private String keyFilePath(List<QemuObject> qemuObjects, String secretId) {
        String flag = secretFlag(qemuObjects, secretId);
        Assert.assertNotNull("no secret object with id " + secretId, flag);
        return flag.replaceAll("^secret,file=", "").replaceAll(",id=.*$", "");
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> capturedOptions() {
        return (Map<String, String>) convertCalls.get(0)[2];
    }

    @SuppressWarnings("unchecked")
    private List<QemuObject> capturedQemuObjects() {
        return (List<QemuObject>) convertCalls.get(0)[3];
    }

    private String capturedSourceImageOptions() {
        return String.join(" ", ((QemuImageOptions) convertCalls.get(0)[4]).toCommandFlag());
    }

    @Test
    public void testCopyPhysicalDiskPassesBothPassphrasesToQemuConvert() throws Exception {
        KVMPhysicalDisk disk = sourceDisk(Storage.StoragePoolType.NetworkFilesystem, PhysicalDiskFormat.QCOW2);
        KVMStoragePool destPool = destinationPool(Storage.StoragePoolType.NetworkFilesystem);

        KVMPhysicalDisk result;
        try (MockedConstruction<QemuImg> ignored = mockQemuImg(qemuInfo("/mnt/src-pool/template.qcow2"), null)) {
            result = libvirtStorageAdaptor.copyPhysicalDisk(disk, DEST_NAME, destPool, 0, SRC_PASSPHRASE, DST_PASSPHRASE,
                    Storage.ProvisioningType.THIN);
        }

        Mockito.verify(destPool).createPhysicalDisk(DEST_NAME, Storage.ProvisioningType.THIN, VIRTUAL_SIZE, DST_PASSPHRASE);

        Assert.assertEquals(1, convertCalls.size());
        List<QemuObject> qemuObjects = capturedQemuObjects();
        Assert.assertEquals(2, qemuObjects.size());
        Assert.assertNotNull(secretFlag(qemuObjects, "sec0"));
        Assert.assertNotNull(secretFlag(qemuObjects, "sec1"));

        Assert.assertEquals("luks", capturedOptions().get("encrypt.format"));
        Assert.assertEquals("sec1", capturedOptions().get("encrypt.key-secret"));

        Assert.assertTrue(capturedSourceImageOptions().contains("encrypt.key-secret=sec0"));
        Assert.assertTrue(capturedSourceImageOptions().contains("file.filename=" + SRC_PATH));
        Assert.assertFalse("the source secret must not leak into the destination -o options",
                capturedSourceImageOptions().contains("sec1"));

        Assert.assertNull(convertCalls.get(0)[5]);
        Assert.assertEquals(Boolean.FALSE, convertCalls.get(0)[6]);
        Assert.assertEquals("--bitmaps is not combined with an encrypted convert", Boolean.FALSE, convertCalls.get(0)[7]);

        Assert.assertEquals(QemuObject.EncryptFormat.LUKS, result.getQemuEncryptFormat());
        Assert.assertEquals(VIRTUAL_SIZE, result.getVirtualSize());

        Assert.assertFalse(Files.exists(Paths.get(keyFilePath(qemuObjects, "sec0"))));
        Assert.assertFalse(Files.exists(Paths.get(keyFilePath(qemuObjects, "sec1"))));
    }

    /** The management server sends an empty passphrase, not a null one, for a volume that has no passphrase at all. */
    private static final byte[] NO_PASSPHRASE = new byte[0];

    @Test
    public void testCopyPhysicalDiskEncryptsAPlaintextSource() throws Exception {
        KVMPhysicalDisk disk = sourceDisk(Storage.StoragePoolType.NetworkFilesystem, PhysicalDiskFormat.QCOW2);
        KVMStoragePool destPool = destinationPool(Storage.StoragePoolType.NetworkFilesystem);

        KVMPhysicalDisk result;
        try (MockedConstruction<QemuImg> ignored = mockQemuImg(qemuInfo("/mnt/src-pool/template.qcow2"), null)) {
            result = libvirtStorageAdaptor.copyPhysicalDisk(disk, DEST_NAME, destPool, 0, NO_PASSPHRASE, DST_PASSPHRASE,
                    Storage.ProvisioningType.THIN);
        }

        Mockito.verify(destPool).createPhysicalDisk(DEST_NAME, Storage.ProvisioningType.THIN, VIRTUAL_SIZE, DST_PASSPHRASE);

        Assert.assertEquals(1, convertCalls.size());
        List<QemuObject> qemuObjects = capturedQemuObjects();
        Assert.assertEquals(1, qemuObjects.size());
        Assert.assertNull("a plaintext source has no secret to read it with", secretFlag(qemuObjects, "sec0"));
        Assert.assertNotNull(secretFlag(qemuObjects, "sec1"));

        Assert.assertEquals("luks", capturedOptions().get("encrypt.format"));
        Assert.assertEquals("sec1", capturedOptions().get("encrypt.key-secret"));
        Assert.assertFalse(capturedSourceImageOptions().contains("encrypt.key-secret"));

        Assert.assertEquals(QemuObject.EncryptFormat.LUKS, result.getQemuEncryptFormat());
        Assert.assertFalse(Files.exists(Paths.get(keyFilePath(qemuObjects, "sec1"))));
    }

    @Test
    public void testCopyPhysicalDiskDecryptsAnEncryptedSource() throws Exception {
        KVMPhysicalDisk disk = sourceDisk(Storage.StoragePoolType.NetworkFilesystem, PhysicalDiskFormat.QCOW2);
        disk.setQemuEncryptFormat(QemuObject.EncryptFormat.LUKS);
        KVMStoragePool destPool = destinationPool(Storage.StoragePoolType.NetworkFilesystem);

        KVMPhysicalDisk result;
        try (MockedConstruction<QemuImg> ignored = mockQemuImg(qemuInfo("/mnt/src-pool/template.qcow2"), null)) {
            result = libvirtStorageAdaptor.copyPhysicalDisk(disk, DEST_NAME, destPool, 0, SRC_PASSPHRASE, NO_PASSPHRASE,
                    Storage.ProvisioningType.THIN);
        }

        Mockito.verify(destPool).createPhysicalDisk(DEST_NAME, Storage.ProvisioningType.THIN, VIRTUAL_SIZE, NO_PASSPHRASE);

        Assert.assertEquals(1, convertCalls.size());
        List<QemuObject> qemuObjects = capturedQemuObjects();
        Assert.assertEquals(1, qemuObjects.size());
        Assert.assertNotNull(secretFlag(qemuObjects, "sec0"));
        Assert.assertNull("nothing may claim the destination is encrypted", secretFlag(qemuObjects, "sec1"));

        Assert.assertTrue("the destination must not be given an encryption format", capturedOptions().isEmpty());
        Assert.assertTrue(capturedSourceImageOptions().contains("encrypt.key-secret=sec0"));

        Assert.assertNull(result.getQemuEncryptFormat());
        Assert.assertFalse(Files.exists(Paths.get(keyFilePath(qemuObjects, "sec0"))));
    }

    @Test
    public void testCopyPhysicalDiskWithoutPassphrasesIsUnchanged() throws Exception {
        KVMPhysicalDisk disk = sourceDisk(Storage.StoragePoolType.NetworkFilesystem, PhysicalDiskFormat.QCOW2);
        KVMStoragePool destPool = destinationPool(Storage.StoragePoolType.NetworkFilesystem);

        KVMPhysicalDisk result;
        try (MockedConstruction<QemuImg> ignored = mockQemuImg(qemuInfo("/mnt/src-pool/template.qcow2"), null)) {
            result = libvirtStorageAdaptor.copyPhysicalDisk(disk, DEST_NAME, destPool, 0, null, null, Storage.ProvisioningType.THIN);
        }

        Mockito.verify(destPool).createPhysicalDisk(DEST_NAME, Storage.ProvisioningType.THIN, VIRTUAL_SIZE, null);

        Assert.assertEquals(1, convertCalls.size());
        Assert.assertTrue("empty options must render exactly as the null argument they replaced", capturedOptions().isEmpty());
        Assert.assertTrue("empty objects must render exactly as the null argument they replaced", capturedQemuObjects().isEmpty());
        Assert.assertEquals(String.join(" ", new QemuImageOptions(PhysicalDiskFormat.QCOW2, SRC_PATH, null).toCommandFlag()),
                capturedSourceImageOptions());
        Assert.assertNull(convertCalls.get(0)[5]);
        Assert.assertEquals(Boolean.FALSE, convertCalls.get(0)[6]);
        Assert.assertEquals(Boolean.TRUE, convertCalls.get(0)[7]);

        Assert.assertNull(result.getQemuEncryptFormat());
    }

    @Test
    public void testCopyPhysicalDiskDoesNotUseFileCopyFastPathWhenEncrypted() throws Exception {
        KVMPhysicalDisk disk = sourceDisk(Storage.StoragePoolType.NetworkFilesystem, PhysicalDiskFormat.QCOW2);
        KVMStoragePool destPool = destinationPool(Storage.StoragePoolType.NetworkFilesystem);

        try (MockedConstruction<QemuImg> ignored = mockQemuImg(qemuInfo(null), null)) {
            libvirtStorageAdaptor.copyPhysicalDisk(disk, DEST_NAME, destPool, 0, SRC_PASSPHRASE, DST_PASSPHRASE,
                    Storage.ProvisioningType.THIN);
        }

        Assert.assertEquals(1, convertCalls.size());
        mockScript.verify(() -> Script.runSimpleBashScript(anyString(), Mockito.anyInt()), never());
    }

    @Test
    public void testCopyPhysicalDiskKeepsFileCopyFastPathWhenUnencrypted() throws Exception {
        KVMPhysicalDisk disk = sourceDisk(Storage.StoragePoolType.NetworkFilesystem, PhysicalDiskFormat.QCOW2);
        KVMStoragePool destPool = destinationPool(Storage.StoragePoolType.NetworkFilesystem);

        try (MockedConstruction<QemuImg> ignored = mockQemuImg(qemuInfo(null), null)) {
            libvirtStorageAdaptor.copyPhysicalDisk(disk, DEST_NAME, destPool, 0, null, null, Storage.ProvisioningType.THIN);
        }

        Assert.assertTrue(convertCalls.isEmpty());
        mockScript.verify(() -> Script.runSimpleBashScript(eq("cp -f " + SRC_PATH + " " + DEST_PATH), eq(0)));
    }

    @Test
    public void testCopyPhysicalDiskClosesKeyFilesWhenConvertFails() throws Exception {
        KVMPhysicalDisk disk = sourceDisk(Storage.StoragePoolType.NetworkFilesystem, PhysicalDiskFormat.QCOW2);
        KVMStoragePool destPool = destinationPool(Storage.StoragePoolType.NetworkFilesystem);

        try (MockedConstruction<QemuImg> ignored = mockQemuImg(qemuInfo("/mnt/src-pool/template.qcow2"), new QemuImgException("boom"))) {
            Assert.assertThrows(CloudRuntimeException.class, () -> libvirtStorageAdaptor.copyPhysicalDisk(disk, DEST_NAME, destPool, 0,
                    SRC_PASSPHRASE, DST_PASSPHRASE, Storage.ProvisioningType.THIN));
        }

        Assert.assertEquals(1, convertCalls.size());
        List<QemuObject> qemuObjects = capturedQemuObjects();
        Assert.assertFalse(Files.exists(Paths.get(keyFilePath(qemuObjects, "sec0"))));
        Assert.assertFalse(Files.exists(Paths.get(keyFilePath(qemuObjects, "sec1"))));
    }

    @Test
    public void testCopyPhysicalDiskRejectsEncryptionWithRbdDestination() {
        KVMPhysicalDisk disk = sourceDisk(Storage.StoragePoolType.NetworkFilesystem, PhysicalDiskFormat.QCOW2);
        KVMStoragePool destPool = destinationPool(Storage.StoragePoolType.RBD);

        CloudRuntimeException e = Assert.assertThrows(CloudRuntimeException.class, () -> libvirtStorageAdaptor.copyPhysicalDisk(disk,
                DEST_NAME, destPool, 0, SRC_PASSPHRASE, DST_PASSPHRASE, Storage.ProvisioningType.THIN));
        Assert.assertTrue(e.getMessage(), e.getMessage().contains("RBD"));
        Assert.assertTrue(convertCalls.isEmpty());
    }

    @Test
    public void testCopyPhysicalDiskRejectsEncryptionWithRbdSource() {
        KVMPhysicalDisk disk = sourceDisk(Storage.StoragePoolType.RBD, PhysicalDiskFormat.RAW);
        KVMStoragePool destPool = destinationPool(Storage.StoragePoolType.NetworkFilesystem);

        CloudRuntimeException e = Assert.assertThrows(CloudRuntimeException.class, () -> libvirtStorageAdaptor.copyPhysicalDisk(disk,
                DEST_NAME, destPool, 0, SRC_PASSPHRASE, DST_PASSPHRASE, Storage.ProvisioningType.THIN));
        Assert.assertTrue(e.getMessage(), e.getMessage().contains("RBD"));
        Assert.assertTrue(convertCalls.isEmpty());
    }

    @Test
    public void testCopyPhysicalDiskRejectsEncryptionForExtractedSourceFormats() {
        for (PhysicalDiskFormat format : Arrays.asList(PhysicalDiskFormat.TAR, PhysicalDiskFormat.DIR)) {
            KVMPhysicalDisk disk = sourceDisk(Storage.StoragePoolType.NetworkFilesystem, format);
            KVMStoragePool destPool = destinationPool(Storage.StoragePoolType.NetworkFilesystem);

            CloudRuntimeException e = Assert.assertThrows(CloudRuntimeException.class, () -> libvirtStorageAdaptor.copyPhysicalDisk(disk,
                    DEST_NAME, destPool, 0, null, DST_PASSPHRASE, Storage.ProvisioningType.THIN));
            Assert.assertTrue(e.getMessage(), e.getMessage().contains(format.toString()));
        }
        Assert.assertTrue(convertCalls.isEmpty());
    }
}
