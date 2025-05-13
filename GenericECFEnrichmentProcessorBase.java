package com.edifecs.ps.enr.facets.components;

import com.edifecs.domain.eehcf.EnrollmentCoverage;
import com.edifecs.domain.eehcf.EnrollmentSubscriber;
import com.edifecs.domain.eehcf.PolicyUnit;
import com.edifecs.domain.hcfd.Enrollment;
import com.edifecs.domain.hcfd.EnrollmentIdentification;
import com.edifecs.etools.commons.io.SmartStream;
import com.edifecs.etools.route.api.ICompositeMessage;
import com.edifecs.etools.route.api.IMessage;
import com.edifecs.etools.route.api.IProcessingContext;
import com.edifecs.platform.ucf.UCFException;
import com.edifecs.platform.ucf.UCFFactory;
import com.edifecs.platform.ucf.domain.*;
import com.edifecs.ps.enr.carefirst.xes.processor.ProcessorBase;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public abstract class GenericECFEnrichmentProcessorBase extends ProcessorBase {
    public static final String POLICY_UNIT = "PolicyUnit";
    public static final String CS_INPUT_TYPE = "CS_InputType";
    private static String enrichmentType;

    @Override
    public void doProcess(IProcessingContext iProcessingContext) throws Exception {
        Thread currentThread = Thread.currentThread();
        ClassLoader contextClassLoader = currentThread.getContextClassLoader();
        currentThread.setContextClassLoader(getClass().getClassLoader());

        try {
            ICompositeMessage inputMessage = iProcessingContext.getInputMessage();
            enrichmentType = iProcessingContext.getContextProperties().get("EnrichmentType");
            Map<String, Object> resultExchangeHeaders = inputMessage.getHeaders();
            IMessage[] messageArray = inputMessage.getMessages();

            if (messageArray.length == 0) {
                createStreamMessage(iProcessingContext, null, resultExchangeHeaders, "data",
                        new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8)));
            } else if (messageArray.length == 1) {
                IMessage iMessage = messageArray[0];
                Map<String, Object> headers = iMessage.getHeaders();
                String cs_inputType = (String) headers.get(CS_INPUT_TYPE);

                if (cs_inputType.equalsIgnoreCase(POLICY_UNIT)) {
                    PolicyUnit policyUnit = getPolicyUnit(iMessage);
                    Map<String, Object> msgHeader = inputMessage.getMessages()[0].getHeaders();
                    String errorMessage = String.format("Call for %s WS  Response Not Received", enrichmentType);
                    ErrorInfo errorInfo = policyUnit.addError(ErrorSeverity.Information, errorMessage);
                    errorInfo.setErrorID(String.format("%sAPICall", enrichmentType));
                    errorInfo.setTechErrorMessage(errorMessage);

                    createStreamMessage(iProcessingContext, msgHeader, resultExchangeHeaders, "data",
                            writeModel(policyUnit).getInputStream());
                }
            } else {
                Map<String, Object> msgHeader = new HashMap<>();
                Arrays.sort(messageArray, new SortMessageArray());
                PolicyUnit policyUnit = null;
                Map<String, Object> puMessageHeaderProperties = null;

                for (IMessage iMessage : messageArray) {
                    msgHeader.putAll(iMessage.getHeaders());
                    String csInputType = (String) msgHeader.get(CS_INPUT_TYPE);
                    log.debug("Process Message with Input Type : {}");

                    if (csInputType != null) {
                        if (POLICY_UNIT.equalsIgnoreCase(csInputType)) {
                            policyUnit = getPolicyUnit(iMessage);
                            puMessageHeaderProperties = iMessage.getHeaders();
                        }
                        if (enrichmentType.equalsIgnoreCase(csInputType)) {
                            try (InputStream wsCallResponseInputStream = iMessage.getBodyAsStream()) {
                                setWSResponseObject(wsCallResponseInputStream, iMessage.getHeaders());
                                log.debug("Message sent to Object");
                            } catch (Exception e) {
                                addErrorMessage(policyUnit, e, "Parsing JSON/XML");
                                log.error(e.getMessage());
                            }
                        }
                    }
                }

                try {
                    enrichECFWithWSResponse(policyUnit, puMessageHeaderProperties);
                    log.debug("GenericECFEnrichmentProcessorBase: Enriching ECF Completed");
                } catch (Exception e) {
                    addErrorMessage(policyUnit, e, "Enriching ECF");
                    log.error(e.getMessage());
                }

                createStreamMessage(iProcessingContext, msgHeader, resultExchangeHeaders, "data",
                        writeModel(policyUnit).getInputStream());
            }
        } finally {
            currentThread.setContextClassLoader(contextClassLoader);
        }
    }

    public void addErrorMessage(PolicyUnit policyUnit, Exception e, String errorMessageStage) {
        String errorMessageFormat = String.format("%s Response Call: Error during %s with %s Response",
                enrichmentType, errorMessageStage, enrichmentType);
        ErrorInfo errorInfo = policyUnit.addError(ErrorSeverity.Warning, errorMessageFormat);
        String message = e.getMessage();
        String errorMessage = message != null && message.length() > 3000 ? message.substring(0, 3000) : message;
        errorInfo.setTechErrorMessage(errorMessage);
        errorInfo.setErrorID(String.format("%sAPICall", enrichmentType));
        log.error(errorMessageFormat);
        log.error("ERROR MESSAGE: " + errorMessage);
    }

    public XMLStreamReader removeEnvelopeAndBodyNodes(InputStream inputStream) throws XMLStreamException {
        XMLInputFactory xif = XMLInputFactory.newFactory();
        XMLStreamReader xsr = xif.createXMLStreamReader(inputStream);
        if (xsr != null) {
            if (xsr.hasNext()) xsr.nextTag();
            if (xsr.hasNext()) xsr.nextTag();
            if (xsr.hasNext()) xsr.nextTag();
        }
        return xsr;
    }

    protected abstract void setWSResponseObject(InputStream inputStream, Map<String, Object> messageHeaderPrp)
            throws Exception;

    protected abstract void enrichECFWithWSResponse(PolicyUnit policyUnit, Map<String, Object> messageHeaderPrp)
            throws Exception;

    private PolicyUnit getPolicyUnit(IMessage message) throws Exception {
        try (InputStream is = message.getBodyAsStream()) {
            UCFReader<Model> reader = UCFFactory.getInstance().getReader();
            Model model = reader.read(is);
            return (PolicyUnit) model;
        }
    }

    public String getStringPropertyValue(Node node, String propertyNameSearch) {
        if (node != null) {
            Collection<Property> ucfCustomProperties = node.getUCFCustomProperties();
            if (ucfCustomProperties != null) {
                for (Property ucfCustomProperty : ucfCustomProperties) {
                    String propertyName = ucfCustomProperty.getPropertyName();
                    if (propertyName != null && propertyName.equalsIgnoreCase(propertyNameSearch)) {
                        return (String) ucfCustomProperty.getValue();
                    }
                }
            }
        }
        return null;
    }

    public static OutputStream writeECF(Model model) throws UCFException {
        UCFFactory factory = UCFFactory.getInstance();
        UCFWriter<Model> ucfWriter = factory.getWriter();
        OutputStream out = new ByteArrayOutputStream();
        ucfWriter.write(out, model);
        return out;
    }

    public static SmartStream writeModel(Model model) throws UCFException, IOException {
        try (SmartStream baos = new SmartStream()) {
            UCFFactory factory = UCFFactory.getInstance();
            UCFWriter writer = factory.getWriter();
            writer.write(baos, model);
            return baos;
        }
    }

    protected static String getGroupIDFromCovLevel(PolicyUnit policyUnit) {
        if (policyUnit != null) {
            List<EnrollmentSubscriber> subscriberList = policyUnit.getSubscriberList();
            if (subscriberList != null && !subscriberList.isEmpty()) {
                List<EnrollmentCoverage> coverageList = subscriberList.get(0).getCoverageList();
                if (coverageList != null && !coverageList.isEmpty()) {
                    EnrollmentCoverage enrollmentCoverage = coverageList.get(0);
                    if (enrollmentCoverage != null) {
                        Enrollment enrollment = enrollmentCoverage.getEnrollment();
                        if (enrollment != null) {
                            EnrollmentIdentification enrollmentIdentification = enrollment.getEnrollmentIdentification();
                            if (enrollmentIdentification != null) {
                                String sourcePolicyIdentifier = enrollmentIdentification.getSourcePolicyIdentifier();
                                if (sourcePolicyIdentifier != null) {
                                    String[] split = sourcePolicyIdentifier.split("-");
                                    if (split != null) {
                                        return split[0];
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return "";
    }
}
