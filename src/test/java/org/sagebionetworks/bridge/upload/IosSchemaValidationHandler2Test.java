package org.sagebionetworks.bridge.upload;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.joda.time.DateTime;
import org.joda.time.DateTimeUtils;
import org.joda.time.LocalDate;
import org.mockito.ArgumentCaptor;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import org.sagebionetworks.bridge.dynamodb.DynamoUpload2;
import org.sagebionetworks.bridge.file.InMemoryFileHelper;
import org.sagebionetworks.bridge.json.BridgeObjectMapper;
import org.sagebionetworks.bridge.time.DateUtils;
import org.sagebionetworks.bridge.models.healthdata.HealthDataRecord;
import org.sagebionetworks.bridge.models.studies.StudyIdentifier;
import org.sagebionetworks.bridge.models.studies.StudyIdentifierImpl;
import org.sagebionetworks.bridge.models.upload.UploadFieldDefinition;
import org.sagebionetworks.bridge.models.upload.UploadFieldType;
import org.sagebionetworks.bridge.models.upload.UploadSchema;
import org.sagebionetworks.bridge.models.upload.UploadSchemaType;
import org.sagebionetworks.bridge.services.UploadSchemaService;

@SuppressWarnings({ "rawtypes", "unchecked" })
public class IosSchemaValidationHandler2Test {
    private static final String TEST_HEALTHCODE = "test-healthcode";
    private static final String TEST_STUDY_ID = "test-study";
    private static final String TEST_UPLOAD_DATE_STRING = "2015-04-13";
    private static final String TEST_UPLOAD_ID = "test-upload";
    private static final DateTime MOCK_NOW = DateTime.parse("2016-05-06T16:36:59.747-0700");

    private static final Map<String, Map<String, Integer>> DEFAULT_SCHEMA_REV_MAP =
            ImmutableMap.of(TEST_STUDY_ID, ImmutableMap.of("schema-rev-test", 2));

    private UploadValidationContext context;
    private IosSchemaValidationHandler2 handler;
    private UploadFileHelper mockUploadFileHelper;
    private InMemoryFileHelper inMemoryFileHelper;
    private File tmpDir;

    @BeforeMethod
    public void setup() {
        DateTimeUtils.setCurrentMillisFixed(MOCK_NOW.getMillis());

        // set up common params for test context
        // dummy study, all we need is the ID
        StudyIdentifier study = new StudyIdentifierImpl(TEST_STUDY_ID);

        // For upload, we need uploadId, healthCode, and uploadDate
        DynamoUpload2 upload = new DynamoUpload2();
        upload.setUploadId(TEST_UPLOAD_ID);
        upload.setHealthCode(TEST_HEALTHCODE);
        upload.setUploadDate(LocalDate.parse(TEST_UPLOAD_DATE_STRING));

        // Create health data record with a blank data map.
        HealthDataRecord record = HealthDataRecord.create();
        record.setData(BridgeObjectMapper.get().createObjectNode());

        context = new UploadValidationContext();
        context.setStudy(study);
        context.setUpload(upload);
        context.setHealthDataRecord(record);

        // Init fileHelper and tmpDir
        inMemoryFileHelper = new InMemoryFileHelper();
        tmpDir = inMemoryFileHelper.createTempDir();

        // set up test schemas
        // To test backwards compatibility, survey schema should include both the old style fields and the new
        // "answers" field.
        UploadSchema surveySchema = UploadSchema.create();
        surveySchema.setStudyId(TEST_STUDY_ID);
        surveySchema.setSchemaId("test-survey");
        surveySchema.setRevision(1);
        surveySchema.setName("iOS Survey");
        surveySchema.setSchemaType(UploadSchemaType.IOS_SURVEY);
        surveySchema.setFieldDefinitions(ImmutableList.of(
                UploadUtil.ANSWERS_FIELD_DEF,

                new UploadFieldDefinition.Builder().withName("foo").withType(UploadFieldType.STRING).build(),
                new UploadFieldDefinition.Builder().withName("bar").withType(UploadFieldType.INT).build(),
                new UploadFieldDefinition.Builder().withName("bar_unit").withType(UploadFieldType.STRING)
                        .build(),
                new UploadFieldDefinition.Builder().withName("baz")
                        .withType(UploadFieldType.ATTACHMENT_JSON_BLOB).build(),
                new UploadFieldDefinition.Builder().withName("calendar-date")
                        .withType(UploadFieldType.CALENDAR_DATE).build(),
                new UploadFieldDefinition.Builder().withName("time-without-date")
                        .withType(UploadFieldType.TIME_V2).build(),
                new UploadFieldDefinition.Builder().withName("legacy-date-time")
                        .withType(UploadFieldType.TIMESTAMP).build(),
                new UploadFieldDefinition.Builder().withName("new-date-time")
                        .withType(UploadFieldType.TIMESTAMP).build(),

                new UploadFieldDefinition.Builder().withName("int-as-string")
                        .withType(UploadFieldType.STRING).build(),
                new UploadFieldDefinition.Builder().withName("timestamp-as-date")
                        .withType(UploadFieldType.CALENDAR_DATE).build(),
                new UploadFieldDefinition.Builder().withName("inline-json-blob")
                        .withType(UploadFieldType.INLINE_JSON_BLOB).build(),

                new UploadFieldDefinition.Builder().withName("optional").withRequired(false)
                        .withType(UploadFieldType.STRING).build(),
                new UploadFieldDefinition.Builder().withName("optional_attachment").withRequired(false)
                        .withType(UploadFieldType.ATTACHMENT_JSON_BLOB).build()));

        UploadSchema nonSurveySchema = UploadSchema.create();
        nonSurveySchema.setStudyId(TEST_STUDY_ID);
        nonSurveySchema.setSchemaId("non-survey");
        nonSurveySchema.setRevision(1);
        nonSurveySchema.setName("Non-Survey");
        nonSurveySchema.setSchemaType(UploadSchemaType.IOS_DATA);
        nonSurveySchema.setFieldDefinitions(ImmutableList.of(new UploadFieldDefinition.Builder()
                .withName("sanitize____attachment.txt").withType(UploadFieldType.ATTACHMENT_V2)
                .withFileExtension(".txt").withMimeType("text/plain").build()));

        // mock upload schema service
        UploadSchemaService mockSchemaService = mock(UploadSchemaService.class);
        when(mockSchemaService.getUploadSchemaByIdAndRevNoThrow(study, "test-survey", 1))
                .thenReturn(surveySchema);
        when(mockSchemaService.getUploadSchemaByIdAndRevNoThrow(study, "non-survey", 1))
                .thenReturn(nonSurveySchema);

        // mock upload file helper
        mockUploadFileHelper = mock(UploadFileHelper.class);

        // set up handler
        handler = new IosSchemaValidationHandler2();
        handler.setDefaultSchemaRevisionMap(DEFAULT_SCHEMA_REV_MAP);
        handler.setFileHelper(inMemoryFileHelper);
        handler.setUploadSchemaService(mockSchemaService);
        handler.setUploadFileHelper(mockUploadFileHelper);
    }

    @AfterMethod
    public void cleanup() {
        DateTimeUtils.setCurrentMillisSystem();
    }

    @Test
    public void survey() throws Exception {
        // fill in context with survey data
        String infoJsonText = "{\n" +
                "   \"files\":[{\n" +
                "       \"filename\":\"foo.json\",\n" +
                "       \"timestamp\":\"2015-04-02T03:26:59-07:00\"\n" +
                "   },{\n" +
                "       \"filename\":\"bar.json\",\n" +
                "       \"timestamp\":\"2015-04-02T03:27:09-07:00\"\n" +
                "   },{\n" +
                "       \"filename\":\"baz.json\",\n" +
                "       \"timestamp\":\"2015-04-02T03:24:01-07:00\"\n" +
                "   },{\n" +
                "       \"filename\":\"calendar-date.json\",\n" +
                "       \"timestamp\":\"2015-04-02T03:24:01-07:00\"\n" +
                "   },{\n" +
                "       \"filename\":\"time-without-date.json\",\n" +
                "       \"timestamp\":\"2015-04-02T03:24:01-07:00\"\n" +
                "   },{\n" +
                "       \"filename\":\"legacy-date-time.json\",\n" +
                "       \"timestamp\":\"2015-04-02T03:24:01-07:00\"\n" +
                "   },{\n" +
                "       \"filename\":\"new-date-time.json\",\n" +
                "       \"timestamp\":\"2015-04-02T03:24:01-07:00\"\n" +
                "   },{\n" +
                "       \"filename\":\"int-as-string.json\",\n" +
                "       \"timestamp\":\"2015-04-02T03:24:01-07:00\"\n" +
                "   },{\n" +
                "       \"filename\":\"timestamp-as-date.json\",\n" +
                "       \"timestamp\":\"2015-04-02T03:24:01-07:00\"\n" +
                "   },{\n" +
                "       \"filename\":\"inline-json-blob.json\",\n" +
                "       \"timestamp\":\"2015-04-02T03:24:01-07:00\"\n" +
                "   }],\n" +
                "   \"item\":\"test-survey\"\n" +
                "}";
        JsonNode infoJsonNode = BridgeObjectMapper.get().readTree(infoJsonText);
        context.setInfoJsonNode(infoJsonNode);

        String fooAnswerJsonText = "{\n" +
                "   \"questionType\":0,\n" +
                "   \"textAnswer\":\"foo answer\",\n" +
                "   \"startDate\":\"2015-04-02T03:26:57-07:00\",\n" +
                "   \"questionTypeName\":\"Text\",\n" +
                "   \"item\":\"foo\",\n" +
                "   \"endDate\":\"2015-04-02T03:26:59-07:00\"\n" +
                "}";

        String barAnswerJsonText = "{\n" +
                "   \"questionType\":0,\n" +
                "   \"numericAnswer\":42,\n" +
                "   \"unit\":\"lb\",\n" +
                "   \"startDate\":\"2015-04-02T03:27:05-07:00\",\n" +
                "   \"questionTypeName\":\"Integer\",\n" +
                "   \"item\":\"bar\",\n" +
                "   \"endDate\":\"2015-04-02T03:27:09-07:00\"\n" +
                "}";

        String bazAnswerJsonText = "{\n" +
                "   \"questionType\":0,\n" +
                "   \"choiceAnswers\":[\"survey\", \"blob\"],\n" +
                "   \"startDate\":\"2015-04-02T03:23:59-07:00\",\n" +
                "   \"questionTypeName\":\"MultipleChoice\",\n" +
                "   \"item\":\"baz\",\n" +
                "   \"endDate\":\"2015-04-02T03:24:01-07:00\"\n" +
                "}";

        String calendarDateAnswerJsonText = "{\n" +
                "   \"questionType\":0,\n" +
                "   \"dateAnswer\":\"2017-01-31\",\n" +
                "   \"startDate\":\"2015-04-02T03:23:59-07:00\",\n" +
                "   \"questionTypeName\":\"Date\",\n" +
                "   \"item\":\"calendar-date\",\n" +
                "   \"endDate\":\"2015-04-02T03:24:01-07:00\"\n" +
                "}";

        String timeWithoutDateAnswerJsonText = "{\n" +
                "   \"questionType\":0,\n" +
                "   \"dateComponentsAnswer\":\"16:42:52.256\",\n" +
                "   \"startDate\":\"2015-04-02T03:23:59-07:00\",\n" +
                "   \"questionTypeName\":\"TimeOfDay\",\n" +
                "   \"item\":\"time-without-date\",\n" +
                "   \"endDate\":\"2015-04-02T03:24:01-07:00\"\n" +
                "}";

        String legacyDateTimeAnswerJsonText = "{\n" +
                "   \"questionType\":0,\n" +
                "   \"dateAnswer\":\"2017-01-31T16:42:52.256-0800\",\n" +
                "   \"startDate\":\"2015-04-02T03:23:59-07:00\",\n" +
                "   \"questionTypeName\":\"Date\",\n" +
                "   \"item\":\"legacy-date-time\",\n" +
                "   \"endDate\":\"2015-04-02T03:24:01-07:00\"\n" +
                "}";

        String newDateTimeAnswerJsonText = "{\n" +
                "   \"questionType\":0,\n" +
                "   \"dateAnswer\":\"2017-02-02T09:13:27.212-0800\",\n" +
                "   \"startDate\":\"2015-04-02T03:23:59-07:00\",\n" +
                "   \"questionTypeName\":\"DateAndTime\",\n" +
                "   \"item\":\"new-date-time\",\n" +
                "   \"endDate\":\"2015-04-02T03:24:01-07:00\"\n" +
                "}";

        String intAsStringAnswerJsonText = "{\n" +
                "   \"questionType\":0,\n" +
                "   \"numericAnswer\":1337,\n" +
                "   \"startDate\":\"2015-04-02T03:27:05-07:00\",\n" +
                "   \"questionTypeName\":\"Integer\",\n" +
                "   \"item\":\"int-as-string\",\n" +
                "   \"endDate\":\"2015-04-02T03:27:09-07:00\"\n" +
                "}";

        String timestampAsDateAnswerJsonText = "{\n" +
                "   \"questionType\":0,\n" +
                "   \"dateAnswer\":\"2015-12-25T14:41-0800\",\n" +
                "   \"startDate\":\"2015-04-02T03:23:59-07:00\",\n" +
                "   \"questionTypeName\":\"DateAndTime\",\n" +
                "   \"item\":\"timestamp-as-date\",\n" +
                "   \"endDate\":\"2015-04-02T03:24:01-07:00\"\n" +
                "}";

        String inlineJsonBlobAnswerJsonText = "{\n" +
                "   \"questionType\":0,\n" +
                "   \"choiceAnswers\":[\"inline\", \"json\", \"blob\"],\n" +
                "   \"startDate\":\"2015-04-02T03:23:59-07:00\",\n" +
                "   \"questionTypeName\":\"MultipleChoice\",\n" +
                "   \"item\":\"inline-json-blob\",\n" +
                "   \"endDate\":\"2015-04-02T03:24:01-07:00\"\n" +
                "}";

        Map<String, File> fileMap = new HashMap<>();
        addFileToMap(fileMap, "foo.json", fooAnswerJsonText);
        addFileToMap(fileMap, "bar.json", barAnswerJsonText);
        addFileToMap(fileMap, "baz.json", bazAnswerJsonText);
        addFileToMap(fileMap, "calendar-date.json", calendarDateAnswerJsonText);
        addFileToMap(fileMap, "time-without-date.json", timeWithoutDateAnswerJsonText);
        addFileToMap(fileMap, "legacy-date-time.json", legacyDateTimeAnswerJsonText);
        addFileToMap(fileMap, "new-date-time.json", newDateTimeAnswerJsonText);
        addFileToMap(fileMap, "int-as-string.json", intAsStringAnswerJsonText);
        addFileToMap(fileMap, "timestamp-as-date.json", timestampAsDateAnswerJsonText);
        addFileToMap(fileMap, "inline-json-blob.json", inlineJsonBlobAnswerJsonText);
        context.setUnzippedDataFileMap(fileMap);

        // execute
        handler.handle(context);

        // validate
        HealthDataRecord record = context.getHealthDataRecord();
        assertEquals(record.getCreatedOn().longValue(), DateTime.parse("2015-04-02T03:27:09-07:00").getMillis());
        assertEquals(record.getCreatedOnTimeZone(), "-0700");
        assertEquals(record.getSchemaId(), "test-survey");
        assertEquals(record.getSchemaRevision().intValue(), 1);

        JsonNode dataNode = record.getData();
        assertEquals(dataNode.size(), 10);
        assertEquals(dataNode.get("foo").textValue(), "foo answer");
        assertEquals(dataNode.get("bar").intValue(), 42);
        assertEquals(dataNode.get("bar_unit").textValue(), "lb");
        assertEquals(dataNode.get("calendar-date").textValue(), "2017-01-31");
        assertEquals(dataNode.get("time-without-date").textValue(), "16:42:52.256");
        assertEquals(dataNode.get("legacy-date-time").textValue(), "2017-01-31T16:42:52.256-0800");
        assertEquals(dataNode.get("new-date-time").textValue(), "2017-02-02T09:13:27.212-0800");
        assertEquals(dataNode.get("int-as-string").textValue(), "1337");
        assertEquals(dataNode.get("timestamp-as-date").textValue(), "2015-12-25");

        JsonNode inlineJsonBlobNode = dataNode.get("inline-json-blob");
        assertEquals(inlineJsonBlobNode.size(), 3);
        assertEquals(inlineJsonBlobNode.get(0).textValue(), "inline");
        assertEquals(inlineJsonBlobNode.get(1).textValue(), "json");
        assertEquals(inlineJsonBlobNode.get(2).textValue(), "blob");

        // "baz" attachment.
        ArgumentCaptor<JsonNode> blobNodeCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(mockUploadFileHelper).uploadJsonNodeAsAttachment(blobNodeCaptor.capture(), eq(TEST_UPLOAD_ID),
                eq("baz"));

        JsonNode blobNode = blobNodeCaptor.getValue();
        assertEquals(blobNode.size(), 2);
        assertEquals(blobNode.get(0).textValue(), "survey");
        assertEquals(blobNode.get(1).textValue(), "blob");

        // Survey "answers" attachment. Note that "answers" is not completely identical to dataNode. This is the raw
        // key-value pairing, so it includes attachments and doesn't canonicalize strings. This is fine, because the
        // old stuff works the same, and we don't want to propagate the iOS-specific formatting hacks to the new stuff.
        ArgumentCaptor<JsonNode> answersNodeCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(mockUploadFileHelper).uploadJsonNodeAsAttachment(answersNodeCaptor.capture(), eq(TEST_UPLOAD_ID),
                eq(UploadUtil.FIELD_ANSWERS));

        JsonNode answersNode = answersNodeCaptor.getValue();
        assertEquals(answersNode.size(), 11);
        assertEquals(answersNode.get("foo").textValue(), "foo answer");
        assertEquals(answersNode.get("bar").intValue(), 42);
        assertEquals(answersNode.get("bar_unit").textValue(), "lb");
        assertEquals(answersNode.get("baz"), blobNode);
        assertEquals(answersNode.get("calendar-date").textValue(), "2017-01-31");
        assertEquals(answersNode.get("time-without-date").textValue(), "16:42:52.256");
        assertEquals(answersNode.get("legacy-date-time").textValue(), "2017-01-31T16:42:52.256-0800");
        assertEquals(answersNode.get("new-date-time").textValue(), "2017-02-02T09:13:27.212-0800");
        assertEquals(answersNode.get("int-as-string").intValue(), 1337);
        assertEquals(answersNode.get("timestamp-as-date").textValue(), "2015-12-25T14:41-0800");
        assertEquals(answersNode.get("inline-json-blob"), inlineJsonBlobNode);

        // We should have no messages.
        assertTrue(context.getMessageList().isEmpty());
    }

    @Test
    public void nonSurvey() throws Exception {
        // Most of the complexities in this class have been moved to UploadFileHelper. As a result, we only need to
        // test 1 field and treat it as a passthrough to UploadFileHelper. The only behavior we need to test is
        // filename sanitization.

        // Mock Upload File Helper
        when(mockUploadFileHelper.findValueForField(eq(TEST_UPLOAD_ID), any(), any(), any())).thenReturn(
                TextNode.valueOf("dummy-attachment-id"));

        // fill in context with JSON data
        String infoJsonText = "{\n" +
                "   \"files\":[{\n" +
                "       \"filename\":\"sanitize!@#$attachment.txt\",\n" +
                "       \"timestamp\":\"2015-04-13T18:47:41-07:00\"\n" +
                "   }],\n" +
                "   \"item\":\"non-survey\"\n" +
                "}";
        JsonNode infoJsonNode = BridgeObjectMapper.get().readTree(infoJsonText);
        context.setInfoJsonNode(infoJsonNode);

        Map<String, File> fileMap = new HashMap<>();
        addFileToMap(fileMap, "sanitize!@#$attachment.txt", "Sanitize my filename");
        context.setUnzippedDataFileMap(fileMap);

        // execute
        handler.handle(context);

        // validate
        HealthDataRecord record = context.getHealthDataRecord();
        assertEquals(record.getCreatedOn().longValue(), DateTime.parse("2015-04-13T18:47:41-07:00").getMillis());
        assertEquals(record.getCreatedOnTimeZone(), "-0700");
        assertEquals(record.getSchemaId(), "non-survey");
        assertEquals(record.getSchemaRevision().intValue(), 1);

        JsonNode dataNode = record.getData();
        assertEquals(dataNode.size(), 1);
        assertEquals(dataNode.get("sanitize____attachment.txt").textValue(), "dummy-attachment-id");

        // Verify call to Upload File Helper
        ArgumentCaptor<Map> sanizitedFileMapCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<UploadFieldDefinition> fieldDefCaptor = ArgumentCaptor.forClass(UploadFieldDefinition.class);
        verify(mockUploadFileHelper).findValueForField(eq(TEST_UPLOAD_ID), sanizitedFileMapCaptor.capture(),
                fieldDefCaptor.capture(), any());

        Map<String, File> sanitizedFileMap = sanizitedFileMapCaptor.getValue();
        assertEquals(sanitizedFileMap.size(), 1);
        assertTrue(sanitizedFileMap.containsKey("sanitize____attachment.txt"));
        assertEquals(sanitizedFileMap.get("sanitize____attachment.txt"), fileMap.get("sanitize!@#$attachment.txt"));

        UploadFieldDefinition fieldDef = fieldDefCaptor.getValue();
        assertEquals(fieldDef.getName(), "sanitize____attachment.txt");

        // We should have no messages.
        assertTrue(context.getMessageList().isEmpty());
    }

    @Test
    public void schemaless() throws Exception {
        // Setup inputs. Create a dummy file, which is ignored because we don't have a schema.
        String infoJsonText = "{\n" +
                "   \"files\":[{\n" +
                "       \"filename\":\"dummy\",\n" +
                "       \"timestamp\":\"2019-05-24T12:44:35.664-07:00\"\n" +
                "   }]\n" +
                "}";
        JsonNode infoJsonNode = BridgeObjectMapper.get().readTree(infoJsonText);
        context.setInfoJsonNode(infoJsonNode);

        Map<String, File> fileMap = new HashMap<>();
        addFileToMap(fileMap, "dummy", "dummy content");
        context.setUnzippedDataFileMap(fileMap);

        // Execute.
        handler.handle(context);

        // Verify that we don't set a schema, but we do set a createdOn.
        HealthDataRecord record = context.getHealthDataRecord();
        assertEquals(record.getCreatedOn().longValue(), DateTime.parse("2019-05-24T12:44:35.664-07:00").getMillis());
        assertEquals(record.getCreatedOnTimeZone(), "-0700");
        assertNull(record.getSchemaId());
        assertNull(record.getSchemaRevision());
        assertTrue(context.getMessageList().isEmpty());

        // Data map is empty. No schema means no data parsed.
        JsonNode dataNode = record.getData();
        assertEquals(dataNode.size(), 0);

        // We never upload anything either.
        verifyZeroInteractions(mockUploadFileHelper);
    }

    @Test
    public void noCreatedOn() throws Exception {
        // Just test defaults for created on. Minimal test. Everything else has been tested elsewhere.

        // fill in context
        String infoJsonText = "{\n" +
                "   \"files\":[{\n" +
                "       \"filename\":\"sanitize!@#$attachment.txt\"\n" +
                "   }],\n" +
                "   \"item\":\"non-survey\",\n" +
                "   \"schemaRevision\":1\n" +
                "}";
        JsonNode infoJsonNode = BridgeObjectMapper.get().readTree(infoJsonText);
        context.setInfoJsonNode(infoJsonNode);

        Map<String, File> fileMap = new HashMap<>();
        addFileToMap(fileMap, "sanitize!@#$attachment.txt", "Sanitize my filename");
        context.setUnzippedDataFileMap(fileMap);

        // execute
        handler.handle(context);

        // validate
        HealthDataRecord record = context.getHealthDataRecord();
        assertEquals(record.getCreatedOn().longValue(), MOCK_NOW.getMillis());
        assertNull(record.getCreatedOnTimeZone());
    }

    @Test
    public void createdOnFromInfoJson() throws Exception {
        // Minimal test w/ createdOn in both file list and info.json, to test createdOn parsing. Everything else has
        // been tested elsewhere.

        // fill in context
        String createdOnString = "2017-10-02T16:45:24.312+09:00";
        long createdOnMillis = DateUtils.convertToMillisFromEpoch(createdOnString);
        String infoJsonText = "{\n" +
                "   \"files\":[{\n" +
                "       \"filename\":\"sanitize!@#$attachment.txt\",\n" +
                "       \"timestamp\":\"2017-10-01T01:13:01.046-07:00\"\n" +
                "   }],\n" +
                "   \"item\":\"non-survey\",\n" +
                "   \"schemaRevision\":1,\n" +
                "   \"createdOn\":\"" + createdOnString + "\"\n" +
                "}";
        JsonNode infoJsonNode = BridgeObjectMapper.get().readTree(infoJsonText);
        context.setInfoJsonNode(infoJsonNode);

        Map<String, File> fileMap = new HashMap<>();
        addFileToMap(fileMap, "sanitize!@#$attachment.txt", "Sanitize my filename");
        context.setUnzippedDataFileMap(fileMap);

        // execute
        handler.handle(context);

        // validate
        HealthDataRecord record = context.getHealthDataRecord();
        assertEquals(record.getCreatedOn().longValue(), createdOnMillis);
        assertEquals(record.getCreatedOnTimeZone(), "+0900");
    }

    private void addFileToMap(Map<String, File> fileMap, String name, String content) {
        File file = inMemoryFileHelper.newFile(tmpDir, name);
        inMemoryFileHelper.writeBytes(file, content.getBytes(Charsets.UTF_8));
        fileMap.put(name, file);
    }
}
