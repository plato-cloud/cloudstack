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

package org.apache.cloudstack.storage.motion;

import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.engine.subsystem.api.storage.DataObject;
import org.apache.cloudstack.secret.dao.PassphraseDao;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;

@RunWith(MockitoJUnitRunner.class)
public class DataMotionServiceImplTest {

    private static final long PASSPHRASE_ID = 42L;

    @Mock
    private VolumeDao volDao;

    @Mock
    private PassphraseDao passphraseDao;

    @InjectMocks
    private DataMotionServiceImpl dataMotionService = new DataMotionServiceImpl();

    private DataObject arrangeFailedMigration(List<VolumeVO> remainingUsers) {
        DataObject srcData = Mockito.mock(DataObject.class);
        DataObject destData = Mockito.mock(DataObject.class);
        Mockito.when(srcData.getId()).thenReturn(1L);
        Mockito.when(destData.getId()).thenReturn(2L);

        VolumeVO sourceVO = Mockito.mock(VolumeVO.class);
        VolumeVO destinationVO = Mockito.mock(VolumeVO.class);
        Mockito.when(volDao.findById(1L)).thenReturn(sourceVO);
        Mockito.when(volDao.findById(2L)).thenReturn(destinationVO);
        Mockito.when(destinationVO.getPassphraseId()).thenReturn(PASSPHRASE_ID);
        Mockito.when(volDao.listVolumesByPassphraseId(PASSPHRASE_ID)).thenReturn(remainingUsers);

        dataMotionService.cleanUpVolumesForFailedMigrations(srcData, destData);

        Mockito.verify(sourceVO).setState(Volume.State.Ready);
        Mockito.verify(destinationVO).setState(Volume.State.Expunged);
        Mockito.verify(destinationVO).setPassphraseId(null);
        return destData;
    }

    @Test
    public void testPassphraseKeptWhileTheSourceVolumeStillReferencesIt() {
        arrangeFailedMigration(Collections.singletonList(Mockito.mock(VolumeVO.class)));

        Mockito.verify(passphraseDao, Mockito.never()).remove(Mockito.anyLong());
    }

    @Test
    public void testPassphraseRemovedWhenNoVolumeReferencesItAnyMore() {
        arrangeFailedMigration(Collections.emptyList());

        Mockito.verify(passphraseDao).remove(PASSPHRASE_ID);
    }

    @Test
    public void testPassphraseRemovedWhenLookupReturnsNull() {
        arrangeFailedMigration(null);

        Mockito.verify(passphraseDao).remove(PASSPHRASE_ID);
    }

    @Test
    public void testNoPassphraseLookupForUnencryptedVolume() {
        DataObject srcData = Mockito.mock(DataObject.class);
        DataObject destData = Mockito.mock(DataObject.class);
        Mockito.when(srcData.getId()).thenReturn(1L);
        Mockito.when(destData.getId()).thenReturn(2L);

        VolumeVO sourceVO = Mockito.mock(VolumeVO.class);
        VolumeVO destinationVO = Mockito.mock(VolumeVO.class);
        Mockito.when(volDao.findById(1L)).thenReturn(sourceVO);
        Mockito.when(volDao.findById(2L)).thenReturn(destinationVO);
        Mockito.when(destinationVO.getPassphraseId()).thenReturn(null);

        dataMotionService.cleanUpVolumesForFailedMigrations(srcData, destData);

        Mockito.verify(volDao, Mockito.never()).listVolumesByPassphraseId(Mockito.anyLong());
        Mockito.verifyNoInteractions(passphraseDao);
        Assert.assertNotNull(destinationVO);
    }
}
