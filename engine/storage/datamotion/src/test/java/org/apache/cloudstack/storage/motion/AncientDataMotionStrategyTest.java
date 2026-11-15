/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cloudstack.storage.motion;

import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.any;

import com.cloud.storage.Storage;
import com.cloud.storage.StorageManager;
import com.cloud.storage.StoragePool;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.ClusterScope;
import org.apache.cloudstack.engine.subsystem.api.storage.HostScope;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.Scope;
import org.apache.cloudstack.engine.subsystem.api.storage.StorageCacheManager;
import org.apache.cloudstack.engine.subsystem.api.storage.ZoneScope;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.storage.heuristics.HeuristicRuleHelper;
import org.apache.cloudstack.storage.image.store.TemplateObject;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.volume.VolumeObject;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.to.DataTO;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.utils.exception.CloudRuntimeException;


@RunWith(MockitoJUnitRunner.class)
public class AncientDataMotionStrategyTest {

    @Spy
    @InjectMocks
    private AncientDataMotionStrategy strategy = new AncientDataMotionStrategy();

    @Mock
    DataTO dataTO;
    @Mock
    PrimaryDataStoreTO dataStoreTO;
    @Mock
    StorageManager storageManager;
    @Mock
    StoragePool storagePool;
    @Mock
    StorageCacheManager cacheMgr;
    @Mock
    ConfigurationDao configDao;
    @Mock
    DataStoreManager dataStoreMgr;
    @Mock
    HeuristicRuleHelper heuristicRuleHelper;

    private static final long POOL_ID = 1l;
    private static final Long PASSPHRASE_ID = 42L;
    private static final String VOLUME_UUID = "0000aaaa-0000-0000-0000-00000000beef";
    private static final String CACHE_STORE_NAME = "fake-cache-store";
    private static final Boolean FULL_CLONE_FLAG = true;

    @Before
    public void setup() throws Exception {
        overrideDefaultConfigValue(StorageManager.VmwareCreateCloneFull, String.valueOf(FULL_CLONE_FLAG));

        when(dataTO.getHypervisorType()).thenReturn(HypervisorType.VMware);
        when(dataTO.getDataStore()).thenReturn(dataStoreTO);
        when(dataStoreTO.getId()).thenReturn(POOL_ID);
        when(storageManager.getStoragePool(POOL_ID)).thenReturn(storagePool);
    }

    private void overrideDefaultConfigValue(final ConfigKey configKey, final String value) throws IllegalAccessException, NoSuchFieldException {
        final Field f = ConfigKey.class.getDeclaredField("_defaultValue");
        f.setAccessible(true);
        f.set(configKey, value);
    }

    @Test
    public void testAddFullCloneFlagOnVMwareDest(){
        strategy.addFullCloneAndDiskprovisiongStrictnessFlagOnVMwareDest(dataTO);
        verify(dataStoreTO).setFullCloneFlag(FULL_CLONE_FLAG);
    }

    @Test
    public void testAddFullCloneFlagOnNotVmwareDest(){
        verify(dataStoreTO, never()).setFullCloneFlag(any(Boolean.class));
    }

    @Test
    public void testCanBypassSecondaryStorageForDirectDownload() {
        VolumeObject srcVolumeInfo = Mockito.spy(new VolumeObject());
        Mockito.doReturn(true).when(srcVolumeInfo).isDirectDownload();

        VolumeObject destVolumeInfo = Mockito.spy(new VolumeObject());

        boolean canBypassSecondaryStorage = strategy.canBypassSecondaryStorage(srcVolumeInfo, destVolumeInfo, false);
        Assert.assertTrue(canBypassSecondaryStorage);
    }

    @Test
    public void testCanBypassSecondaryStorageForUnsupportedDataObject() {
        VolumeObject srcVolumeInfo = Mockito.spy(new VolumeObject());
        Mockito.doReturn(HypervisorType.KVM).when(srcVolumeInfo).getHypervisorType();

        TemplateObject destTemplateInfo = Mockito.spy(new TemplateObject());

        boolean canBypassSecondaryStorage = strategy.canBypassSecondaryStorage(srcVolumeInfo, destTemplateInfo, false);
        Assert.assertFalse(canBypassSecondaryStorage);
    }

    @Test
    public void testCanBypassSecondaryStorageForUnsupportedSrcPoolType() {
        VolumeObject srcVolumeInfo = Mockito.spy(new VolumeObject());
        Mockito.doReturn(HypervisorType.KVM).when(srcVolumeInfo).getHypervisorType();
        DataStore srcDataStore = Mockito.mock(DataStore.class);
        Mockito.doReturn(new ZoneScope(1L)).when(srcDataStore).getScope();
        Mockito.doReturn(srcDataStore).when(srcVolumeInfo).getDataStore();
        Mockito.doReturn(Storage.StoragePoolType.PowerFlex).when(srcVolumeInfo).getStoragePoolType();

        VolumeObject destVolumeInfo = Mockito.spy(new VolumeObject());
        Mockito.doReturn(HypervisorType.KVM).when(destVolumeInfo).getHypervisorType();
        DataStore destDataStore = Mockito.mock(DataStore.class);
        Mockito.doReturn(new ZoneScope(1L)).when(destDataStore).getScope();
        Mockito.doReturn(destDataStore).when(destVolumeInfo).getDataStore();
        Mockito.doReturn(Storage.StoragePoolType.NetworkFilesystem).when(destVolumeInfo).getStoragePoolType();

        boolean canBypassSecondaryStorage = strategy.canBypassSecondaryStorage(srcVolumeInfo, destVolumeInfo, false);
        Assert.assertFalse(canBypassSecondaryStorage);
    }

    @Test
    public void testCanBypassSecondaryStorageForUnsupportedDestPoolType() {
        VolumeObject srcVolumeInfo = Mockito.spy(new VolumeObject());
        Mockito.doReturn(HypervisorType.KVM).when(srcVolumeInfo).getHypervisorType();
        DataStore srcDataStore = Mockito.mock(DataStore.class);
        Mockito.doReturn(new ZoneScope(1L)).when(srcDataStore).getScope();
        Mockito.doReturn(srcDataStore).when(srcVolumeInfo).getDataStore();
        Mockito.doReturn(Storage.StoragePoolType.NetworkFilesystem).when(srcVolumeInfo).getStoragePoolType();

        VolumeObject destVolumeInfo = Mockito.spy(new VolumeObject());
        Mockito.doReturn(HypervisorType.KVM).when(destVolumeInfo).getHypervisorType();
        DataStore destDataStore = Mockito.mock(DataStore.class);
        Mockito.doReturn(new ZoneScope(1L)).when(destDataStore).getScope();
        Mockito.doReturn(destDataStore).when(destVolumeInfo).getDataStore();
        Mockito.doReturn(Storage.StoragePoolType.Iscsi).when(destVolumeInfo).getStoragePoolType();

        boolean canBypassSecondaryStorage = strategy.canBypassSecondaryStorage(srcVolumeInfo, destVolumeInfo, false);
        Assert.assertFalse(canBypassSecondaryStorage);
    }

    @Test
    public void testCanBypassSecondaryStorageWithZoneWideNFSPoolsInSameZone() {
        VolumeObject srcVolumeInfo = Mockito.spy(new VolumeObject());
        Mockito.doReturn(HypervisorType.KVM).when(srcVolumeInfo).getHypervisorType();
        DataStore srcDataStore = Mockito.mock(DataStore.class);
        Mockito.doReturn(new ZoneScope(1L)).when(srcDataStore).getScope();
        Mockito.doReturn(srcDataStore).when(srcVolumeInfo).getDataStore();
        Mockito.doReturn(Storage.StoragePoolType.NetworkFilesystem).when(srcVolumeInfo).getStoragePoolType();

        VolumeObject destVolumeInfo = Mockito.spy(new VolumeObject());
        Mockito.doReturn(HypervisorType.KVM).when(destVolumeInfo).getHypervisorType();
        DataStore destDataStore = Mockito.mock(DataStore.class);
        Mockito.doReturn(new ZoneScope(1L)).when(destDataStore).getScope();
        Mockito.doReturn(destDataStore).when(destVolumeInfo).getDataStore();
        Mockito.doReturn(Storage.StoragePoolType.NetworkFilesystem).when(destVolumeInfo).getStoragePoolType();

        boolean canBypassSecondaryStorage = strategy.canBypassSecondaryStorage(srcVolumeInfo, destVolumeInfo, false);
        Assert.assertTrue(canBypassSecondaryStorage);
    }

    @Test
    public void testCanBypassSecondaryStorageWithClusterWideNFSPoolsInSameCluster() {
        VolumeObject srcVolumeInfo = Mockito.spy(new VolumeObject());
        Mockito.doReturn(HypervisorType.KVM).when(srcVolumeInfo).getHypervisorType();
        DataStore srcDataStore = Mockito.mock(DataStore.class);
        Mockito.doReturn(new ClusterScope(5L, 2L, 1L)).when(srcDataStore).getScope();
        Mockito.doReturn(srcDataStore).when(srcVolumeInfo).getDataStore();
        Mockito.doReturn(Storage.StoragePoolType.NetworkFilesystem).when(srcVolumeInfo).getStoragePoolType();

        VolumeObject destVolumeInfo = Mockito.spy(new VolumeObject());
        Mockito.doReturn(HypervisorType.KVM).when(destVolumeInfo).getHypervisorType();
        DataStore destDataStore = Mockito.mock(DataStore.class);
        Mockito.doReturn(new ClusterScope(5L, 2L, 1L)).when(destDataStore).getScope();
        Mockito.doReturn(destDataStore).when(destVolumeInfo).getDataStore();
        Mockito.doReturn(Storage.StoragePoolType.NetworkFilesystem).when(destVolumeInfo).getStoragePoolType();

        boolean canBypassSecondaryStorage = strategy.canBypassSecondaryStorage(srcVolumeInfo, destVolumeInfo, false);
        Assert.assertTrue(canBypassSecondaryStorage);
    }

    @Test
    public void testCanBypassSecondaryStorageWithLocalAndClusterWideNFSPoolsInSameCluster() {
        VolumeObject srcVolumeInfo = Mockito.spy(new VolumeObject());
        Mockito.doReturn(HypervisorType.KVM).when(srcVolumeInfo).getHypervisorType();
        DataStore srcDataStore = Mockito.mock(DataStore.class);
        Mockito.doReturn(new HostScope(1L, 1L, 1L)).when(srcDataStore).getScope();
        Mockito.doReturn(srcDataStore).when(srcVolumeInfo).getDataStore();
        Mockito.doReturn(Storage.StoragePoolType.Filesystem).when(srcVolumeInfo).getStoragePoolType();

        VolumeObject destVolumeInfo = Mockito.spy(new VolumeObject());
        Mockito.doReturn(HypervisorType.KVM).when(destVolumeInfo).getHypervisorType();
        DataStore destDataStore = Mockito.mock(DataStore.class);
        Mockito.doReturn(new ClusterScope(1L, 1L, 1L)).when(destDataStore).getScope();
        Mockito.doReturn(destDataStore).when(destVolumeInfo).getDataStore();
        Mockito.doReturn(Storage.StoragePoolType.NetworkFilesystem).when(destVolumeInfo).getStoragePoolType();

        boolean canBypassSecondaryStorage = strategy.canBypassSecondaryStorage(srcVolumeInfo, destVolumeInfo, false);
        Assert.assertTrue(canBypassSecondaryStorage);

        canBypassSecondaryStorage = strategy.canBypassSecondaryStorage(destVolumeInfo, srcVolumeInfo, false);
        Assert.assertTrue(canBypassSecondaryStorage);
    }

    @Test
    public void testCanBypassSecondaryStorageWithLocalAndZoneWideNFSPoolsInSameZone() {
        VolumeObject srcVolumeInfo = Mockito.spy(new VolumeObject());
        Mockito.doReturn(HypervisorType.KVM).when(srcVolumeInfo).getHypervisorType();
        DataStore srcDataStore = Mockito.mock(DataStore.class);
        Mockito.doReturn(new HostScope(1L, 1L, 1L)).when(srcDataStore).getScope();
        Mockito.doReturn(srcDataStore).when(srcVolumeInfo).getDataStore();
        Mockito.doReturn(Storage.StoragePoolType.Filesystem).when(srcVolumeInfo).getStoragePoolType();

        VolumeObject destVolumeInfo = Mockito.spy(new VolumeObject());
        Mockito.doReturn(HypervisorType.KVM).when(destVolumeInfo).getHypervisorType();
        DataStore destDataStore = Mockito.mock(DataStore.class);
        Mockito.doReturn(new ZoneScope(1L)).when(destDataStore).getScope();
        Mockito.doReturn(destDataStore).when(destVolumeInfo).getDataStore();
        Mockito.doReturn(Storage.StoragePoolType.NetworkFilesystem).when(destVolumeInfo).getStoragePoolType();

        boolean canBypassSecondaryStorage = strategy.canBypassSecondaryStorage(srcVolumeInfo, destVolumeInfo, false);
        Assert.assertTrue(canBypassSecondaryStorage);

        canBypassSecondaryStorage = strategy.canBypassSecondaryStorage(destVolumeInfo, srcVolumeInfo, false);
        Assert.assertTrue(canBypassSecondaryStorage);
    }

    @Test
    public void testCanBypassSecondaryStorageWithClusterWideNFSAndZoneWideNFSPoolsInSameZone() {
        VolumeObject srcVolumeInfo = Mockito.spy(new VolumeObject());
        Mockito.doReturn(HypervisorType.KVM).when(srcVolumeInfo).getHypervisorType();
        DataStore srcDataStore = Mockito.mock(DataStore.class);
        Mockito.doReturn(new ClusterScope(5L, 2L, 1L)).when(srcDataStore).getScope();
        Mockito.doReturn(srcDataStore).when(srcVolumeInfo).getDataStore();
        Mockito.doReturn(Storage.StoragePoolType.NetworkFilesystem).when(srcVolumeInfo).getStoragePoolType();

        VolumeObject destVolumeInfo = Mockito.spy(new VolumeObject());
        Mockito.doReturn(HypervisorType.KVM).when(destVolumeInfo).getHypervisorType();
        DataStore destDataStore = Mockito.mock(DataStore.class);
        Mockito.doReturn(new ZoneScope(1L)).when(destDataStore).getScope();
        Mockito.doReturn(destDataStore).when(destVolumeInfo).getDataStore();
        Mockito.doReturn(Storage.StoragePoolType.NetworkFilesystem).when(destVolumeInfo).getStoragePoolType();

        boolean canBypassSecondaryStorage = strategy.canBypassSecondaryStorage(srcVolumeInfo, destVolumeInfo, false);
        Assert.assertTrue(canBypassSecondaryStorage);

        canBypassSecondaryStorage = strategy.canBypassSecondaryStorage(destVolumeInfo, srcVolumeInfo, false);
        Assert.assertTrue(canBypassSecondaryStorage);
    }

    private VolumeObject volumeOn(Storage.StoragePoolType poolType, Scope scope) {
        VolumeObject volume = Mockito.spy(new VolumeObject());
        Mockito.doReturn(HypervisorType.KVM).when(volume).getHypervisorType();
        Mockito.doReturn(poolType).when(volume).getStoragePoolType();
        DataStore dataStore = Mockito.mock(DataStore.class);
        Mockito.doReturn(scope).when(dataStore).getScope();
        Mockito.doReturn(dataStore).when(volume).getDataStore();
        return volume;
    }

    /**
     * A volume whose own {@code pool_type} column was never filled in, so only the pool it sits on knows its type.
     */
    private VolumeObject volumeOnPool(Storage.StoragePoolType poolType, Scope scope) {
        VolumeObject volume = Mockito.spy(new VolumeObject());
        Mockito.doReturn(HypervisorType.KVM).when(volume).getHypervisorType();
        PrimaryDataStore dataStore = Mockito.mock(PrimaryDataStore.class);
        Mockito.doReturn(scope).when(dataStore).getScope();
        Mockito.doReturn(poolType).when(dataStore).getPoolType();
        Mockito.doReturn(dataStore).when(volume).getDataStore();
        return volume;
    }

    private VolumeObject encryptedVolumeOn(Storage.StoragePoolType poolType, Scope scope) {
        VolumeObject volume = volumeOn(poolType, scope);
        Mockito.doReturn(PASSPHRASE_ID).when(volume).getPassphraseId();
        Mockito.doReturn(VOLUME_UUID).when(volume).getUuid();
        return volume;
    }

    @Test
    public void testCanBypassSecondaryStorageForEncryptedVolumeBetweenZoneWideNFSPools() {
        VolumeObject srcVolumeInfo = volumeOn(Storage.StoragePoolType.NetworkFilesystem, new ZoneScope(1L));
        VolumeObject destVolumeInfo = volumeOn(Storage.StoragePoolType.NetworkFilesystem, new ZoneScope(1L));

        Assert.assertTrue(strategy.canBypassSecondaryStorage(srcVolumeInfo, destVolumeInfo, true));
    }

    @Test
    public void testCanBypassSecondaryStorageForEncryptedVolumeBetweenSharedMountPointAndLocalPool() {
        VolumeObject srcVolumeInfo = volumeOn(Storage.StoragePoolType.SharedMountPoint, new ClusterScope(1L, 1L, 1L));
        VolumeObject destVolumeInfo = volumeOn(Storage.StoragePoolType.Filesystem, new HostScope(1L, 1L, 1L));

        Assert.assertTrue(strategy.canBypassSecondaryStorage(srcVolumeInfo, destVolumeInfo, true));
        Assert.assertTrue(strategy.canBypassSecondaryStorage(destVolumeInfo, srcVolumeInfo, true));
    }

    @Test
    public void testCanBypassSecondaryStorageIgnoresSharedMountPointWithoutEncryption() {
        VolumeObject srcVolumeInfo = volumeOn(Storage.StoragePoolType.SharedMountPoint, new ZoneScope(1L));
        VolumeObject destVolumeInfo = volumeOn(Storage.StoragePoolType.NetworkFilesystem, new ZoneScope(1L));

        Assert.assertFalse(strategy.canBypassSecondaryStorage(srcVolumeInfo, destVolumeInfo, false));
    }

    @Test
    public void testCanBypassSecondaryStorageOnRbdOnlyWithoutEncryption() {
        VolumeObject srcVolumeInfo = volumeOn(Storage.StoragePoolType.RBD, new ZoneScope(1L));
        VolumeObject destVolumeInfo = volumeOn(Storage.StoragePoolType.NetworkFilesystem, new ZoneScope(1L));

        Assert.assertTrue(strategy.canBypassSecondaryStorage(srcVolumeInfo, destVolumeInfo, false));
        Assert.assertFalse(strategy.canBypassSecondaryStorage(srcVolumeInfo, destVolumeInfo, true));
    }

    @Test
    public void testCopyVolumeBetweenPoolsRefusesEncryptedVolumeOnUnsupportedPoolType() {
        VolumeObject srcVolumeInfo = encryptedVolumeOn(Storage.StoragePoolType.RBD, new ZoneScope(1L));
        VolumeObject destVolumeInfo = volumeOn(Storage.StoragePoolType.NetworkFilesystem, new ZoneScope(1L));

        CloudRuntimeException e = Assert.assertThrows(CloudRuntimeException.class,
                () -> strategy.copyVolumeBetweenPools(srcVolumeInfo, destVolumeInfo));

        Assert.assertTrue(e.getMessage(), e.getMessage().contains(VOLUME_UUID));
        Assert.assertTrue(e.getMessage(), e.getMessage().contains("this migration is from RBD to NetworkFilesystem"));
    }

    @Test
    public void testCopyVolumeBetweenPoolsRefusesEncryptedVolumeBetweenUnreachableScopes() {
        VolumeObject srcVolumeInfo = encryptedVolumeOn(Storage.StoragePoolType.NetworkFilesystem, new ClusterScope(1L, 1L, 1L));
        VolumeObject destVolumeInfo = volumeOn(Storage.StoragePoolType.NetworkFilesystem, new ClusterScope(2L, 2L, 1L));

        CloudRuntimeException e = Assert.assertThrows(CloudRuntimeException.class,
                () -> strategy.copyVolumeBetweenPools(srcVolumeInfo, destVolumeInfo));

        Assert.assertTrue(e.getMessage(), e.getMessage().contains("no single host reaches both pools"));
        Assert.assertTrue(e.getMessage(), e.getMessage().contains("source pool scope is CLUSTER 1"));
        Assert.assertTrue(e.getMessage(), e.getMessage().contains("destination pool scope is CLUSTER 2"));
    }

    @Test
    public void testCopyVolumeBetweenPoolsRefusesEncryptedVolumeStagedThroughACacheStore() {
        VolumeObject srcVolumeInfo = encryptedVolumeOn(Storage.StoragePoolType.NetworkFilesystem, new ZoneScope(1L));
        VolumeObject destVolumeInfo = volumeOn(Storage.StoragePoolType.NetworkFilesystem, new ZoneScope(1L));

        DataStore cacheStore = Mockito.mock(DataStore.class);
        Mockito.doReturn(CACHE_STORE_NAME).when(cacheStore).getName();
        Mockito.doReturn(cacheStore).when(cacheMgr).getCacheStorage(Mockito.any(Scope.class));

        CloudRuntimeException e = Assert.assertThrows(CloudRuntimeException.class,
                () -> strategy.copyVolumeBetweenPools(srcVolumeInfo, destVolumeInfo));

        Assert.assertTrue(e.getMessage(), e.getMessage().contains("stages every copy through image cache store " + CACHE_STORE_NAME));
    }

    @Test
    public void testCopyVolumeBetweenPoolsStillStagesAnUnencryptedVolumeThroughSecondaryStorage() {
        VolumeObject srcVolumeInfo = volumeOn(Storage.StoragePoolType.SharedMountPoint, new ZoneScope(1L));
        Mockito.doReturn(null).when(srcVolumeInfo).getPassphraseId();
        VolumeObject destVolumeInfo = volumeOn(Storage.StoragePoolType.NetworkFilesystem, new ZoneScope(1L));
        Mockito.doReturn(null).when(destVolumeInfo).getPassphraseId();

        Answer answer = strategy.copyVolumeBetweenPools(srcVolumeInfo, destVolumeInfo);

        Assert.assertFalse(answer.getResult());
        Assert.assertTrue(answer.getDetails(), answer.getDetails().contains("image store"));
    }

    @Test
    public void testCanBypassSecondaryStorageForEncryptedVolumeWhoseRowHasNoPoolType() {
        VolumeObject srcVolumeInfo = volumeOnPool(Storage.StoragePoolType.NetworkFilesystem, new ClusterScope(1L, 1L, 1L));
        VolumeObject destVolumeInfo = volumeOnPool(Storage.StoragePoolType.Filesystem, new HostScope(1L, 1L, 1L));

        Assert.assertTrue(strategy.canBypassSecondaryStorage(srcVolumeInfo, destVolumeInfo, true));
        verify(srcVolumeInfo, never()).getStoragePoolType();
        verify(destVolumeInfo, never()).getStoragePoolType();
    }

    @Test
    public void testCanBypassSecondaryStorageForUnencryptedVolumeWhoseRowHasNoPoolType() {
        VolumeObject srcVolumeInfo = volumeOnPool(Storage.StoragePoolType.NetworkFilesystem, new ZoneScope(1L));
        VolumeObject destVolumeInfo = volumeOnPool(Storage.StoragePoolType.RBD, new ZoneScope(1L));

        Assert.assertTrue(strategy.canBypassSecondaryStorage(srcVolumeInfo, destVolumeInfo, false));
    }
}
