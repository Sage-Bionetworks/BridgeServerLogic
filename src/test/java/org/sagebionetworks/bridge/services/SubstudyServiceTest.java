package org.sagebionetworks.bridge.services;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.util.List;
import java.util.Set;

import org.joda.time.DateTime;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import org.sagebionetworks.bridge.TestConstants;
import org.sagebionetworks.bridge.dao.SubstudyDao;
import org.sagebionetworks.bridge.exceptions.EntityAlreadyExistsException;
import org.sagebionetworks.bridge.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.models.VersionHolder;
import org.sagebionetworks.bridge.models.substudies.Substudy;

import com.google.common.collect.ImmutableList;

public class SubstudyServiceTest {
    private static final List<Substudy> SUBSTUDIES = ImmutableList.of(Substudy.create(), Substudy.create());
    private static final VersionHolder VERSION_HOLDER = new VersionHolder(1L);
    
    @Mock
    private SubstudyDao substudyDao;
    
    @Captor
    private ArgumentCaptor<Substudy> substudyCaptor;
    
    private SubstudyService service;
    
    @BeforeMethod
    public void before() {
        MockitoAnnotations.initMocks(this);
        
        service = new SubstudyService();
        service.setSubstudyDao(substudyDao);
    }
    
    @Test
    public void getSubstudy() {
        Substudy substudy = Substudy.create();
        when(substudyDao.getSubstudy(TestConstants.TEST_STUDY, "id")).thenReturn(substudy);
        
        Substudy returnedValue = service.getSubstudy(TestConstants.TEST_STUDY, "id", true);
        assertEquals(returnedValue, substudy);
        
        verify(substudyDao).getSubstudy(TestConstants.TEST_STUDY, "id");
    }
    
    @Test
    public void getSubstudyIds() {
        Substudy substudyA = Substudy.create();
        substudyA.setId("substudyA");
        
        Substudy substudyB = Substudy.create();
        substudyB.setId("substudyB");
        List<Substudy> substudies = ImmutableList.of(substudyA, substudyB); 
        
        when(substudyDao.getSubstudies(TestConstants.TEST_STUDY, false)).thenReturn(substudies);
        
        Set<String> substudyIds = service.getSubstudyIds(TestConstants.TEST_STUDY);
        assertEquals(substudyIds, TestConstants.USER_SUBSTUDY_IDS);
    }
    
    @Test(expectedExceptions = EntityNotFoundException.class)
    public void getSubstudyNotFoundThrowingException() {
        service.getSubstudy(TestConstants.TEST_STUDY, "id", true);
    }
    
    @Test
    public void getSubstudyNotFoundNotThrowingException() {
        Substudy substudy = service.getSubstudy(TestConstants.TEST_STUDY, "id", false);
        assertNull(substudy);
    }
    
    @Test
    public void getSubstudiesIncludeDeleted() {
        when(substudyDao.getSubstudies(TestConstants.TEST_STUDY, true)).thenReturn(SUBSTUDIES);
        
        List<Substudy> returnedValue = service.getSubstudies(TestConstants.TEST_STUDY, true);
        assertEquals(returnedValue, SUBSTUDIES);
        
        verify(substudyDao).getSubstudies(TestConstants.TEST_STUDY, true);
    }
    
    @Test
    public void getSubstudiesExcludeDeleted() {
        when(substudyDao.getSubstudies(TestConstants.TEST_STUDY, false)).thenReturn(SUBSTUDIES);
        
        List<Substudy> returnedValue = service.getSubstudies(TestConstants.TEST_STUDY, false);
        assertEquals(returnedValue, SUBSTUDIES);
        
        verify(substudyDao).getSubstudies(TestConstants.TEST_STUDY, false);
    }
    
    @Test
    public void createSubstudy() {
        Substudy substudy = Substudy.create();
        substudy.setId("oneId");
        substudy.setName("oneName");
        substudy.setStudyId("junk");
        substudy.setVersion(10L);
        substudy.setDeleted(true);
        DateTime timestamp = DateTime.now().minusHours(2);
        substudy.setCreatedOn(timestamp);
        substudy.setModifiedOn(timestamp);

        when(substudyDao.createSubstudy(any())).thenReturn(VERSION_HOLDER);
        
        VersionHolder returnedValue = service.createSubstudy(TestConstants.TEST_STUDY, substudy);
        assertEquals(returnedValue, VERSION_HOLDER);
        
        verify(substudyDao).createSubstudy(substudyCaptor.capture());
        
        Substudy persisted = substudyCaptor.getValue();
        assertEquals(persisted.getId(), "oneId");
        assertEquals(persisted.getName(), "oneName");
        assertEquals(persisted.getStudyId(), TestConstants.TEST_STUDY_IDENTIFIER);
        assertNull(persisted.getVersion());
        assertFalse(persisted.isDeleted());
        assertNotEquals(persisted.getCreatedOn(), timestamp);
        assertNotEquals(persisted.getModifiedOn(), timestamp);
    }
    
    @Test(expectedExceptions = InvalidEntityException.class)
    public void createSubstudyInvalidSubstudy() {
        service.createSubstudy(TestConstants.TEST_STUDY, Substudy.create());
    }
    
    @Test(expectedExceptions = EntityAlreadyExistsException.class)
    public void createSubstudyAlreadyExists() {
        Substudy substudy = Substudy.create();
        substudy.setId("oneId");
        substudy.setName("oneName");
        
        when(substudyDao.getSubstudy(TestConstants.TEST_STUDY, "oneId")).thenReturn(substudy);
        
        service.createSubstudy(TestConstants.TEST_STUDY, substudy);
    }

    @Test
    public void updateSubstudy() {
        Substudy existing = Substudy.create();
        existing.setId("oneId");
        existing.setName("oldName");
        existing.setCreatedOn(DateTime.now());
        when(substudyDao.getSubstudy(TestConstants.TEST_STUDY, "oneId")).thenReturn(existing);
        when(substudyDao.updateSubstudy(any())).thenReturn(VERSION_HOLDER);

        Substudy substudy = Substudy.create();
        substudy.setStudyId("wrongStudyId");
        substudy.setId("oneId");
        substudy.setName("newName");
        
        VersionHolder versionHolder = service.updateSubstudy(TestConstants.TEST_STUDY, substudy);
        assertEquals(versionHolder, VERSION_HOLDER);
        
        verify(substudyDao).updateSubstudy(substudyCaptor.capture());
        
        Substudy returnedValue = substudyCaptor.getValue();
        assertEquals(returnedValue.getStudyId(), TestConstants.TEST_STUDY_IDENTIFIER);
        assertEquals(returnedValue.getId(), "oneId");
        assertEquals(returnedValue.getName(), "newName");
        assertNotNull(returnedValue.getCreatedOn());
        assertNotNull(returnedValue.getModifiedOn());
    }
    
    @Test(expectedExceptions = InvalidEntityException.class)
    public void updateSubstudyInvalidSubstudy() {
        service.updateSubstudy(TestConstants.TEST_STUDY, Substudy.create());
    }
    
    @Test(expectedExceptions = EntityNotFoundException.class)
    public void updateSubstudyEntityNotFound() {
        Substudy substudy = Substudy.create();
        substudy.setId("oneId");
        substudy.setName("oneName");
        substudy.setDeleted(true);

        service.updateSubstudy(TestConstants.TEST_STUDY, substudy);
    }
    
    @Test(expectedExceptions = EntityNotFoundException.class)
    public void updateSubstudyEntityDeleted() {
        Substudy existing = Substudy.create();
        existing.setDeleted(true);
        when(substudyDao.getSubstudy(TestConstants.TEST_STUDY, "oneId")).thenReturn(existing);

        Substudy substudy = Substudy.create();
        substudy.setId("oneId");
        substudy.setName("oneName");
        substudy.setDeleted(true);
        
        service.updateSubstudy(TestConstants.TEST_STUDY, substudy);
    }

    @Test
    public void deleteSubstudy() {
        when(substudyDao.getSubstudy(TestConstants.TEST_STUDY, "id")).thenReturn(Substudy.create());
        
        service.deleteSubstudy(TestConstants.TEST_STUDY, "id");
        
        verify(substudyDao).updateSubstudy(substudyCaptor.capture());
        Substudy persisted = substudyCaptor.getValue();
        assertTrue(persisted.isDeleted());
        assertNotNull(persisted.getModifiedOn());
    }
    
    @Test(expectedExceptions = EntityNotFoundException.class)
    public void deleteSubstudyNotFound() {
        service.deleteSubstudy(TestConstants.TEST_STUDY, "id");
    }
    
    @Test
    public void deleteSubstudyPermanently() {
        when(substudyDao.getSubstudy(TestConstants.TEST_STUDY, "id")).thenReturn(Substudy.create());
        
        service.deleteSubstudyPermanently(TestConstants.TEST_STUDY, "id");
        
        verify(substudyDao).deleteSubstudyPermanently(TestConstants.TEST_STUDY, "id");
    }    

    @Test(expectedExceptions = EntityNotFoundException.class)
    public void deleteSubstudyPermanentlyNotFound() {
        service.deleteSubstudyPermanently(TestConstants.TEST_STUDY, "id");
    }    
}
