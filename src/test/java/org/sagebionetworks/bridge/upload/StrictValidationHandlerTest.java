package org.sagebionetworks.bridge.upload;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import static org.sagebionetworks.bridge.TestConstants.TEST_STUDY;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import org.sagebionetworks.bridge.dynamodb.DynamoStudy;
import org.sagebionetworks.bridge.dynamodb.DynamoUpload2;
import org.sagebionetworks.bridge.json.BridgeObjectMapper;
import org.sagebionetworks.bridge.models.healthdata.HealthDataRecord;
import org.sagebionetworks.bridge.models.upload.UploadFieldDefinition;
import org.sagebionetworks.bridge.models.upload.UploadFieldType;
import org.sagebionetworks.bridge.models.upload.UploadSchema;
import org.sagebionetworks.bridge.models.upload.UploadValidationStrictness;
import org.sagebionetworks.bridge.services.StudyService;
import org.sagebionetworks.bridge.services.UploadSchemaService;

public class StrictValidationHandlerTest {
    private final static byte[] DUMMY_ATTACHMENT = new byte[0];
    private final static String DUMMY_ATTACHMENT_ID = "dummy-attachment-id";

    private UploadValidationContext context;
    private StrictValidationHandler handler;

    @BeforeMethod
    public void setup() {
        handler = new StrictValidationHandler();

        // Set up common context attributes.
        context = new UploadValidationContext();
        context.setStudy(TEST_STUDY);

        DynamoUpload2 upload = new DynamoUpload2();
        upload.setUploadId("test-upload");
        context.setUpload(upload);
    }

    private void test(List<UploadFieldDefinition> additionalFieldDefList, Map<String, byte[]> additionalAttachmentMap,
            JsonNode additionalJsonNode, List<String> expectedErrorList,
            UploadValidationStrictness uploadValidationStrictness) throws Exception {
        // Basic schema with a basic attachment, basic field, and additional fields.
        UploadSchema testSchema = UploadSchema.create();
        List<UploadFieldDefinition> fieldDefList = new ArrayList<>();
        fieldDefList.add(new UploadFieldDefinition.Builder().withName("attachment blob")
                .withType(UploadFieldType.ATTACHMENT_BLOB).build());
        fieldDefList.add(new UploadFieldDefinition.Builder().withName("string")
                .withType(UploadFieldType.STRING).build());
        if (additionalFieldDefList != null) {
            fieldDefList.addAll(additionalFieldDefList);
        }
        testSchema.setFieldDefinitions(fieldDefList);

        // mock schema service
        UploadSchemaService mockSchemaService = mock(UploadSchemaService.class);
        when(mockSchemaService.getUploadSchemaByIdAndRev(TEST_STUDY, "test-schema", 1)).thenReturn(
                testSchema);
        handler.setUploadSchemaService(mockSchemaService);

        // mock study service - this is to get the shouldThrow (strictUploadValidationEnabled) flag
        DynamoStudy testStudy = new DynamoStudy();
        testStudy.setUploadValidationStrictness(uploadValidationStrictness);

        StudyService mockStudyService = mock(StudyService.class);
        when(mockStudyService.getStudy(TEST_STUDY)).thenReturn(testStudy);
        handler.setStudyService(mockStudyService);

        // set up JSON data
        String jsonDataString = "{\n" +
                "   \"string\":\"This is a string\"\n" +
                "}";
        ObjectNode jsonDataNode = (ObjectNode) BridgeObjectMapper.get().readTree(jsonDataString);
        if (additionalJsonNode != null) {
            ObjectNode additionalObjectNode = (ObjectNode) additionalJsonNode;
            Iterator<Map.Entry<String, JsonNode>> additionalJsonIter = additionalObjectNode.fields();
            while (additionalJsonIter.hasNext()) {
                Map.Entry<String, JsonNode> oneAdditionalJson = additionalJsonIter.next();
                jsonDataNode.set(oneAdditionalJson.getKey(), oneAdditionalJson.getValue());
            }
        }

        // We now upload attachments before we call StrictValidationHandler. To handle this, write another entry into
        // the jsonDataNode with a dummy attachment ID.
        jsonDataNode.put("attachment blob", DUMMY_ATTACHMENT_ID);
        if (additionalAttachmentMap != null) {
            for (String oneAttachmentName : additionalAttachmentMap.keySet()) {
                jsonDataNode.put(oneAttachmentName, DUMMY_ATTACHMENT_ID);
            }
        }

        // write JSON data to health data record builder
        HealthDataRecord record = HealthDataRecord.create();
        record.setData(jsonDataNode);
        record.setSchemaId("test-schema");
        record.setSchemaRevision(1);
        context.setHealthDataRecord(record);

        if (expectedErrorList == null || expectedErrorList.isEmpty()) {
            // In all test cases, we successfully handle and have no error messages.
            handler.handle(context);
            assertTrue(context.getMessageList().isEmpty());
        } else {
            if (uploadValidationStrictness == UploadValidationStrictness.STRICT) {
                // If strict, we need to catch that exception.
                try {
                    handler.handle(context);
                    fail("Expected exception");
                } catch (UploadValidationException ex) {
                    for (String oneExpectedError : expectedErrorList) {
                        assertTrue(ex.getMessage().contains(oneExpectedError), "Expected error: " + oneExpectedError);
                    }
                }
            } else {
                // Handle normally.
                handler.handle(context);
            }

            // Error messages in context.
            // We don't want to do string matching. Instead, the quickest way to verify this is to concatenate the
            // context message list together and make sure our expected strings are in there.
            String concatMessage = Joiner.on('\n').join(context.getMessageList());
            for (String oneExpectedError : expectedErrorList) {
                assertTrue(concatMessage.contains(oneExpectedError), "Expected error: " + oneExpectedError);
            }

            if (uploadValidationStrictness == UploadValidationStrictness.REPORT) {
                // If strictness is REPORT, do the same for record.validationErrors.
                String recordValidationErrors = record.getValidationErrors();
                for (String oneExpectedError : expectedErrorList) {
                    assertTrue(recordValidationErrors.contains(oneExpectedError),
                            "Expected error: " + oneExpectedError);
                }
            }
        }
    }

    @Test
    public void happyCase() throws Exception {
        // additional field defs
        // Test one of each type.
        List<UploadFieldDefinition> additionalFieldDefList = ImmutableList.of(
                new UploadFieldDefinition.Builder().withName("attachment csv")
                        .withType(UploadFieldType.ATTACHMENT_CSV).build(),
                new UploadFieldDefinition.Builder().withName("attachment json blob")
                        .withType(UploadFieldType.ATTACHMENT_JSON_BLOB).build(),
                new UploadFieldDefinition.Builder().withName("attachment json table")
                        .withType(UploadFieldType.ATTACHMENT_JSON_TABLE).build(),
                new UploadFieldDefinition.Builder().withName("boolean")
                        .withType(UploadFieldType.BOOLEAN).build(),
                new UploadFieldDefinition.Builder().withName("calendar date")
                        .withType(UploadFieldType.CALENDAR_DATE).build(),
                new UploadFieldDefinition.Builder().withName("float")
                        .withType(UploadFieldType.FLOAT).build(),
                new UploadFieldDefinition.Builder().withName("float with int value")
                        .withType(UploadFieldType.FLOAT).build(),
                new UploadFieldDefinition.Builder().withName("inline json blob")
                        .withType(UploadFieldType.INLINE_JSON_BLOB).build(),
                new UploadFieldDefinition.Builder().withName("int")
                        .withType(UploadFieldType.INT).build(),
                new UploadFieldDefinition.Builder().withName("int with float value")
                        .withType(UploadFieldType.INT).build(),
                new UploadFieldDefinition.Builder().withName("multi-choice")
                        .withType(UploadFieldType.MULTI_CHOICE).withMultiChoiceAnswerList("foo", "bar", "baz").build(),
                new UploadFieldDefinition.Builder().withName("delicious")
                        .withType(UploadFieldType.MULTI_CHOICE).withMultiChoiceAnswerList("Yes", "No")
                        .withAllowOtherChoices(true).build(),
                new UploadFieldDefinition.Builder().withName("string timestamp")
                        .withType(UploadFieldType.TIMESTAMP).build(),
                new UploadFieldDefinition.Builder().withName("long timestamp")
                        .withType(UploadFieldType.TIMESTAMP).build(),
                new UploadFieldDefinition.Builder().withName("missing optional attachment")
                        .withType(UploadFieldType.ATTACHMENT_BLOB).withRequired(false).build(),
                new UploadFieldDefinition.Builder().withName("present optional attachment")
                        .withType(UploadFieldType.ATTACHMENT_BLOB).withRequired(false).build(),
                new UploadFieldDefinition.Builder().withName("missing optional json")
                        .withType(UploadFieldType.STRING).withRequired(false).build(),
                new UploadFieldDefinition.Builder().withName("present optional json")
                        .withType(UploadFieldType.STRING).withRequired(false).build());

        // additional attachments map
        Map<String, byte[]> additionalAttachmentsMap = ImmutableMap.of(
                "attachment csv", DUMMY_ATTACHMENT,
                "attachment json blob", DUMMY_ATTACHMENT,
                "attachment json table", DUMMY_ATTACHMENT,
                "present optional attachment", DUMMY_ATTACHMENT);

        // additional JSON data
        String additionalJsonText = "{\n" +
                "   \"boolean\":true,\n" +
                "   \"calendar date\":\"2015-07-24\",\n" +
                "   \"float\":3.14,\n" +
                "   \"float with int value\":13,\n" +
                "   \"inline json blob\":[\"inline\", \"json\", \"blob\"],\n" +
                "   \"int\":42,\n" +
                "   \"int with float value\":2.78,\n" +
                "   \"multi-choice\":[\"foo\", \"bar\", \"baz\"],\n" +
                "   \"delicious\":[\"Yes\", \"Maybe\"],\n" +
                "   \"string timestamp\":\"2015-07-24T18:49:54-07:00\",\n" +
                "   \"long timestamp\":1437787098066,\n" +
                "   \"present optional json\":\"optional, but present\"\n" +
                "}";
        JsonNode additionalJsonNode = BridgeObjectMapper.get().readTree(additionalJsonText);

        // execute and validate
        test(additionalFieldDefList, additionalAttachmentsMap, additionalJsonNode, null,
                UploadValidationStrictness.STRICT);
    }

    @Test
    public void canonicalizedValue() throws Exception {
        // additional field defs
        List<UploadFieldDefinition> additionalFieldDefList = ImmutableList.of(
                new UploadFieldDefinition.Builder().withName("canonicalized int").withType(UploadFieldType.INT)
                        .build());

        // additional JSON data
        String additionalJsonText = "{\n" +
                "   \"canonicalized int\":\"23\"\n" +
                "}";
        JsonNode additionalJsonNode = BridgeObjectMapper.get().readTree(additionalJsonText);

        // execute and validate
        test(additionalFieldDefList, null, additionalJsonNode, null, UploadValidationStrictness.STRICT);

        // verify canonicalized value
        JsonNode dataNode = context.getHealthDataRecord().getData();
        JsonNode intNode = dataNode.get("canonicalized int");
        assertTrue(intNode.isIntegralNumber());
        assertEquals(intNode.intValue(), 23);
    }

    @Test
    public void invalidValue() throws Exception {
        // additional field defs
        List<UploadFieldDefinition> additionalFieldDefList = ImmutableList.of(
                new UploadFieldDefinition.Builder().withName("invalid int").withType(UploadFieldType.INT)
                        .build());

        // additional JSON data
        String additionalJsonText = "{\n" +
                "   \"invalid int\":\"ninety-nine\"\n" +
                "}";
        JsonNode additionalJsonNode = BridgeObjectMapper.get().readTree(additionalJsonText);

        // expected errors
        List<String> expectedErrorList = ImmutableList.of("invalid int");

        // execute and validate
        test(additionalFieldDefList, null, additionalJsonNode, expectedErrorList, UploadValidationStrictness.STRICT);
    }

    @Test
    public void invalidMultiChoice() throws Exception {
        // additional field defs
        List<UploadFieldDefinition> additionalFieldDefList = ImmutableList.of(
                new UploadFieldDefinition.Builder().withName("invalid multi-choice")
                        .withType(UploadFieldType.MULTI_CHOICE).withMultiChoiceAnswerList("good1", "good2").build());

        // additional JSON data
        String additionalJsonText = "{\n" +
                "   \"invalid multi-choice\":[\"bad1\", \"good2\", \"bad2\"]\n" +
                "}";
        JsonNode additionalJsonNode = BridgeObjectMapper.get().readTree(additionalJsonText);

        // expected errors
        List<String> expectedErrorList = ImmutableList.of(
                "Multi-Choice field invalid multi-choice contains invalid answer bad1",
                "Multi-Choice field invalid multi-choice contains invalid answer bad2");

        // execute and validate
        test(additionalFieldDefList, null, additionalJsonNode, expectedErrorList, UploadValidationStrictness.STRICT);
    }

    @Test
    public void missingRequiredAttachment() throws Exception {
        // additional field defs
        List<UploadFieldDefinition> additionalFieldDefList = ImmutableList.of(
                new UploadFieldDefinition.Builder().withName("missing required attachment")
                        .withType(UploadFieldType.ATTACHMENT_BLOB).build());

        // expected errors
        List<String> expectedErrorList = ImmutableList.of("missing required attachment");

        // execute and validate
        test(additionalFieldDefList, null, null, expectedErrorList, UploadValidationStrictness.STRICT);
    }

    @Test
    public void missingRequiredField() throws Exception {
        // additional field defs
        List<UploadFieldDefinition> additionalFieldDefList = ImmutableList.of(
                new UploadFieldDefinition.Builder().withName("missing required field")
                        .withType(UploadFieldType.STRING).build());

        // expected errors
        List<String> expectedErrorList = ImmutableList.of("missing required field");

        // execute and validate
        test(additionalFieldDefList, null, null, expectedErrorList, UploadValidationStrictness.STRICT);
    }

    @Test
    public void optionalFieldStillGetsValidated() throws Exception {
        // additional field defs
        List<UploadFieldDefinition> additionalFieldDefList = ImmutableList.of(
                new UploadFieldDefinition.Builder().withName("optional int")
                        .withType(UploadFieldType.INT).withRequired(false).build());

        // additional JSON data
        String additionalJsonText = "{\n" +
                "   \"optional int\":false\n" +
                "}";
        JsonNode additionalJsonNode = BridgeObjectMapper.get().readTree(additionalJsonText);

        // expected errors
        List<String> expectedErrorList = ImmutableList.of("optional int");

        // execute and validate
        test(additionalFieldDefList, null, additionalJsonNode, expectedErrorList, UploadValidationStrictness.STRICT);
    }

    @Test
    public void jsonNullRequiredField() throws Exception {
        // additional field defs
        List<UploadFieldDefinition> additionalFieldDefList = ImmutableList.of(
                new UploadFieldDefinition.Builder().withName("null required field")
                        .withType(UploadFieldType.INLINE_JSON_BLOB).build());

        // additional JSON data
        String additionalJsonText = "{\n" +
                "   \"null required field\":null\n" +
                "}";
        JsonNode additionalJsonNode = BridgeObjectMapper.get().readTree(additionalJsonText);

        // expected errors
        List<String> expectedErrorList = ImmutableList.of("null required field");

        // execute and validate
        test(additionalFieldDefList, null, additionalJsonNode, expectedErrorList, UploadValidationStrictness.STRICT);
    }

    @Test
    public void multipleValidationErrors() throws Exception {
        // additional field defs
        List<UploadFieldDefinition> additionalFieldDefList = ImmutableList.of(
                new UploadFieldDefinition.Builder().withName("missing required attachment")
                        .withType(UploadFieldType.ATTACHMENT_BLOB).build(),
                new UploadFieldDefinition.Builder().withName("invalid int")
                        .withType(UploadFieldType.INT).build());

        // additional JSON data
        String additionalJsonText = "{\n" +
                "   \"invalid int\":\"Math.PI\"\n" +
                "}";
        JsonNode additionalJsonNode = BridgeObjectMapper.get().readTree(additionalJsonText);

        // expected errors
        List<String> expectedErrorList = ImmutableList.of("missing required attachment", "invalid int");

        // execute and validate
        test(additionalFieldDefList, null, additionalJsonNode, expectedErrorList, UploadValidationStrictness.STRICT);
    }

    @Test
    public void studyConfiguredToNotThrow() throws Exception {
        // additional field defs
        List<UploadFieldDefinition> additionalFieldDefList = ImmutableList.of(
                new UploadFieldDefinition.Builder().withName("missing required field")
                        .withType(UploadFieldType.STRING).build());

        // expected errors
        List<String> expectedErrorList = ImmutableList.of("missing required field");

        // execute and validate
        test(additionalFieldDefList, null, null, expectedErrorList, UploadValidationStrictness.WARNING);
    }

    @Test
    public void strictnessWarningWithNoErrors() throws Exception {
        // No additional fields, data, or errors.
        test(null, null, null, null, UploadValidationStrictness.WARNING);
    }

    @Test
    public void strictnessReportWithErrors() throws Exception {
        // additional field defs
        List<UploadFieldDefinition> additionalFieldDefList = ImmutableList.of(new UploadFieldDefinition.Builder()
                .withName("missing required field").withType(UploadFieldType.STRING).build());

        // expected errors
        List<String> expectedErrorList = ImmutableList.of("missing required field");

        // execute and validate
        test(additionalFieldDefList, null, null, expectedErrorList, UploadValidationStrictness.REPORT);
    }

    @Test
    public void strictnessReportWithNoErrors() throws Exception {
        // No additional fields, data, or errors.
        test(null, null, null, null, UploadValidationStrictness.REPORT);
    }

    @Test
    public void schemaless() throws Exception {
        // Set up mocks.
        StudyService mockStudyService = mock(StudyService.class);
        UploadSchemaService mockUploadSchemaService = mock(UploadSchemaService.class);
        handler.setStudyService(mockStudyService);
        handler.setUploadSchemaService(mockUploadSchemaService);

        // Create record with no schema.
        HealthDataRecord record = HealthDataRecord.create();
        record.setData(BridgeObjectMapper.get().createObjectNode());
        record.setSchemaId(null);
        record.setSchemaRevision(null);
        context.setHealthDataRecord(record);

        // Execute. No error messages.
        handler.handle(context);
        assertTrue(context.getMessageList().isEmpty());

        // We don't ever use the dependent services.
        verifyZeroInteractions(mockStudyService, mockUploadSchemaService);
    }
}
