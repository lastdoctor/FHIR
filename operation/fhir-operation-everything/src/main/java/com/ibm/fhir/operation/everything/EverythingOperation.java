/*
 * (C) Copyright IBM Corp. 2021, 2022
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.fhir.operation.everything;

import static com.ibm.fhir.model.util.ModelSupport.FHIR_DATE;
import static com.ibm.fhir.model.util.ModelSupport.FHIR_INSTANT;
import static com.ibm.fhir.model.util.ModelSupport.FHIR_STRING;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;

import com.ibm.fhir.config.FHIRConfigHelper;
import com.ibm.fhir.config.FHIRConfiguration;
import com.ibm.fhir.config.FHIRRequestContext;
import com.ibm.fhir.config.Interaction;
import com.ibm.fhir.config.PropertyGroup;
import com.ibm.fhir.config.ResourcesConfigAdapter;
import com.ibm.fhir.core.FHIRConstants;
import com.ibm.fhir.core.HTTPHandlingPreference;
import com.ibm.fhir.exception.FHIROperationException;
import com.ibm.fhir.model.resource.Bundle;
import com.ibm.fhir.model.resource.Bundle.Entry;
import com.ibm.fhir.model.resource.OperationDefinition;
import com.ibm.fhir.model.resource.Parameters;
import com.ibm.fhir.model.resource.Parameters.Parameter;
import com.ibm.fhir.model.resource.Patient;
import com.ibm.fhir.model.resource.Resource;
import com.ibm.fhir.model.resource.SearchParameter;
import com.ibm.fhir.model.type.Reference;
import com.ibm.fhir.model.type.UnsignedInt;
import com.ibm.fhir.model.type.Uri;
import com.ibm.fhir.model.type.code.BundleType;
import com.ibm.fhir.model.type.code.HTTPVerb;
import com.ibm.fhir.model.type.code.IssueType;
import com.ibm.fhir.model.type.code.ResourceType;
import com.ibm.fhir.model.util.ModelSupport;
import com.ibm.fhir.model.util.ReferenceFinder;
import com.ibm.fhir.persistence.exception.FHIRPersistenceResourceDeletedException;
import com.ibm.fhir.registry.FHIRRegistry;
import com.ibm.fhir.search.SearchConstants;
import com.ibm.fhir.search.compartment.CompartmentHelper;
import com.ibm.fhir.search.exception.FHIRSearchException;
import com.ibm.fhir.search.exception.SearchExceptionUtil;
import com.ibm.fhir.search.util.ReferenceUtil;
import com.ibm.fhir.search.util.ReferenceValue;
import com.ibm.fhir.search.util.SearchHelper;
import com.ibm.fhir.server.spi.operation.AbstractOperation;
import com.ibm.fhir.server.spi.operation.FHIROperationContext;
import com.ibm.fhir.server.spi.operation.FHIROperationUtil;
import com.ibm.fhir.server.spi.operation.FHIRResourceHelpers;


/**
 * This class implements the <a href="https://www.hl7.org/fhir/operation-patient-everything.html">$everything</a> operation
 * which is used to return all the information related to one or more patients described in the resource or context on
 * which this operation is invoked.
 */
public class EverythingOperation extends AbstractOperation {

    private static final Logger LOG = java.util.logging.Logger.getLogger(EverythingOperation.class.getName());

    /**
     * The <a href="https://www.hl7.org/fhir/search.html#prefix">prefix</a> used to indicate the start date for the $everything resources
     */
    protected static final String STARTING_FROM = SearchConstants.Prefix.GE.value();

    /**
     * The <a href="https://www.hl7.org/fhir/search.html#prefix">prefix</a> used to indicate the end date for the $everything resources
     */
    protected static final String UP_UNTIL = SearchConstants.Prefix.LE.value();

    /**
     * The "date" query parameter used in the underlying search operation.
     */
    protected static final String DATE_QUERY_PARAMETER = "date";

    /**
     * The "_lastUpdated" query parameter used in the underlying search operation.
     */
    protected static final String LAST_UPDATED_QUERY_PARAMETER = "_lastUpdated";

    /**
     * The query parameter to indicate a start date for the $everything operation
     */
    protected static final String START_QUERY_PARAMETER = "start";

    /**
     * The query parameter to indicate a stop date for the $everything operation
     */
    protected static final String END_QUERY_PARAMETER = "end";

    /**
     * The query parameter to only return resources last update since a date for the $everything operation
     */
    protected static final String SINCE_QUERY_PARAMETER = "_since";

    /**
     * The patient resource name
     */
    private static final String PATIENT = Patient.class.getSimpleName();

    /**
     * The maximum number of cumulative resources from all compartments for a given patient.
     */
    private static final int MAX_OVERALL_RESOURCES = 10000;

    /**
     * The list of resources for which the <code>date</code> query parameter can be used
     */
    private static final Set<String> SUPPORT_CLINICAL_DATE_QUERY = new HashSet<>(Arrays.asList(
        "AllergyIntolerance",
        "CarePlan",
        "CareTeam",
        "ClinicalImpression",
        "Composition",
        "Consent",
        "DiagnosticReport",
        "Encounter",
        "EpisodeOfCare",
        "FamilyMemberHistory",
        "Flag",
        "Immunization",
        "List",
        "Observation",
        "Procedure",
        "RiskAssessment",
        "SupplyRequest"));

    private final CompartmentHelper compartmentHelper = new CompartmentHelper();

    @Override
    protected OperationDefinition buildOperationDefinition() {
        return FHIRRegistry.getInstance().getResource("http://hl7.org/fhir/OperationDefinition/Patient-everything",
                OperationDefinition.class);
    }

    @Override
    protected Parameters doInvoke(FHIROperationContext operationContext, Class<? extends Resource> resourceType, String logicalId,
            String versionId, Parameters parameters, FHIRResourceHelpers resourceHelper, SearchHelper searchHelper)
            throws FHIROperationException {
        LOG.entering(this.getClass().getName(), "doInvoke");

        /* Per the specification, If there is no nominated patient (GET /Patient/$everything) and the context is not associated with a single patient record,
         * the actual list of patients is all patients that the user associated with the request has access to. This may be all patients
         * in the family that the patient has access to, or it may be all patients that a care provider has access to, or all patients
         * on the entire record system. In such cases, the server may choose to return an error rather than all the records.
         *
         * @implNote we do not currently support it. However, if we do, we can use Patient?link=Patient/<SmartLaunch Context Patient ID>
         * @see Issue #2402 for more details.
         */
        if (logicalId == null) {
            throw buildExceptionWithIssue("This implementation requires the Patient's logical id to be passed; "
                    + "the patient / set of patients cannot be inferred from the request context.", IssueType.NOT_SUPPORTED);
        }

        Patient patient = null;
        try {
            patient = (Patient) resourceHelper.doRead(PATIENT, logicalId, false, false, null).getResource();
        } catch (FHIRPersistenceResourceDeletedException fde) {
            FHIROperationException exceptionWithIssue = buildExceptionWithIssue("Patient with ID '" + logicalId + "' "
                    + "does not exist.", IssueType.NOT_FOUND);
            throw exceptionWithIssue;
        } catch (Exception e) {
            FHIROperationException exceptionWithIssue = buildExceptionWithIssue("An unexpected error occurred while "
                    + "reading patient '" + logicalId + "'", IssueType.EXCEPTION, e);
            LOG.throwing(this.getClass().getName(), "doInvoke", exceptionWithIssue);
            throw exceptionWithIssue;
        }
        if (patient == null) {
            FHIROperationException exceptionWithIssue = buildExceptionWithIssue("Patient with ID '" + logicalId + "' "
                    + "does not exist.", IssueType.NOT_FOUND);
            throw exceptionWithIssue;
        }

        int maxPageSize = Math.max(1, FHIRConfigHelper.getIntProperty("fhirServer/core/maxPageSize", FHIRConstants.FHIR_PAGE_SIZE_DEFAULT_MAX));
        List<Entry> allEntries = new ArrayList<>(maxPageSize);
        List<String> resourceIds = new ArrayList<String>();
        // Look up which extra resources should be returned
        List<String> extraResources = new ArrayList<String>();
        try {
            PropertyGroup parentResourcePropGroup = FHIRConfigHelper.getPropertyGroup(FHIRConfiguration.PROPERTY_OPERATIONS_EVERYTHING);
            if (parentResourcePropGroup != null) {
                extraResources = parentResourcePropGroup.getStringListProperty(FHIRConfiguration.PROPERTY_OPERATIONS_EVERYTHING_INCLUDE_TYPES);
            }
        } catch (Exception e) {
            FHIROperationException exceptionWithIssue = buildExceptionWithIssue("Error retrieving configuration of $everything ",
                IssueType.EXCEPTION, e);
            LOG.throwing(this.getClass().getName(), "doInvoke", exceptionWithIssue);
            throw exceptionWithIssue;
        }

        // We can't always use the "date" query parameter to query by clinical date, only with some resources.
        // Initial list obtained from the github issue: https://github.com/IBM/FHIR/issues/1044#issuecomment-769788097
        // Otherwise the search throws an exception. We create a params map with and without and use as needed
        MultivaluedMap<String, String> queryParameters = parseQueryParameters(parameters, maxPageSize);
        MultivaluedMap<String, String> queryParametersWithoutDates = new MultivaluedHashMap<String,String>(queryParameters);
        boolean startOrEndProvided = queryParametersWithoutDates.remove(DATE_QUERY_PARAMETER) != null;

        List<String> defaultResourceTypes = new ArrayList<String>(0);
        try {
            defaultResourceTypes = getDefaultIncludedResourceTypes(resourceHelper);
        } catch (FHIRSearchException e) {
            throw new Error("There has been an error retrieving the list of included resources of the $everything operation.", e);
        }

        List<String> resourceTypes = getOverridenIncludedResourceTypes(parameters, defaultResourceTypes);
        if (resourceTypes.isEmpty()) {
            resourceTypes = defaultResourceTypes;
        }

        boolean retrieveAdditionalResources = extraResources != null && !extraResources.isEmpty();
        for (String compartmentMemberType : resourceTypes) {
            MultivaluedMap<String, String> searchParameters = queryParameters;
            if (startOrEndProvided  && !SUPPORT_CLINICAL_DATE_QUERY.contains(compartmentMemberType)) {
                if (LOG.isLoggable(Level.FINEST)) {
                    LOG.finest("The request specified a '" + START_QUERY_PARAMETER + "' and/or '" + END_QUERY_PARAMETER + "' query parameter. They are not valid for resource type '" + compartmentMemberType + "', so will be ignored.");
                }
                searchParameters = queryParametersWithoutDates;
            }

            // Need to construct a new version of the map each time through the loop to edit
            MultivaluedMap<String, String> tempSearchParameters =
                    new MultivaluedHashMap<String, String>(searchParameters);

            Bundle results = null;
            int currentResourceCount = 0;
            try {
                // Don't need to add more search parameters if the config section isn't there or is empty
                if (retrieveAdditionalResources) {
                    addIncludesSearchParameters(compartmentMemberType, tempSearchParameters, extraResources, searchHelper);
                }
                results = resourceHelper.doSearch(compartmentMemberType, PATIENT, logicalId, tempSearchParameters, null, null);
                int countBeforeAddingNewResources = allEntries.size();
                processResults(results, compartmentMemberType, results.getEntry(), allEntries, resourceIds, resourceHelper, extraResources, retrieveAdditionalResources);
                currentResourceCount = results.getTotal().getValue();
                if (LOG.isLoggable(Level.FINEST)) {
                    LOG.finest("Got " + compartmentMemberType + " resources " + (allEntries.size() - countBeforeAddingNewResources) + " for a total of " + allEntries.size());
                }
            } catch (Exception e) {
                FHIROperationException exceptionWithIssue = buildExceptionWithIssue("Error retrieving $everything "
                        + "related resources of type '" + compartmentMemberType + "' for patient " + logicalId, IssueType.EXCEPTION, e);
                LOG.throwing(this.getClass().getName(), "doInvoke", exceptionWithIssue);
                throw exceptionWithIssue;
            }
            // If retrieving all these resources exceeds the maximum number of resources allowed for this operation the operation is failed
            if (allEntries.size() > MAX_OVERALL_RESOURCES) {
                FHIROperationException exceptionWithIssue = buildExceptionWithIssue("The maximum number of resources "
                        + "allowed for the $everything operation (" + MAX_OVERALL_RESOURCES + ") has been exceeded "
                        + "for patient '" + logicalId + "'. Try using the bulkexport feature.", IssueType.TOO_COSTLY);
                LOG.throwing(this.getClass().getName(), "doInvoke", exceptionWithIssue);
                throw exceptionWithIssue;
            }

            // We are retrieving sub-resources maxPageSize items at a time, but there could be more so we need to retrieve the rest of the pages for the last resource if needed
            if (currentResourceCount > maxPageSize) {
                // We already retrieved page 1 so we account for that and start retrieving the rest of the pages
                int page = 2;
                while ((currentResourceCount -= maxPageSize) > 0) {
                    if (LOG.isLoggable(Level.FINEST)) {
                        LOG.finest("Retrieving page " + page + " of the " + compartmentMemberType + " resources for patient " + logicalId);
                    }
                    try {
                        tempSearchParameters.putSingle(SearchConstants.PAGE, page++ + "");
                        results = resourceHelper.doSearch(compartmentMemberType, PATIENT, logicalId, tempSearchParameters, null, null);
                        processResults(results, compartmentMemberType, results.getEntry(), allEntries, resourceIds, resourceHelper, extraResources, retrieveAdditionalResources);
                    } catch (Exception e) {
                        FHIROperationException exceptionWithIssue = buildExceptionWithIssue("Error retrieving "
                                + "$everything resources page '" + page + "' of type '" + compartmentMemberType + "' "
                                + "for patient " + logicalId, IssueType.EXCEPTION, e);
                        LOG.throwing(this.getClass().getName(), "doInvoke", exceptionWithIssue);
                        throw exceptionWithIssue;
                    }
                    // If retrieving all these resources exceeds the maximum number of resources allowed for this operation the operation is failed
                    if (allEntries.size() > MAX_OVERALL_RESOURCES) {
                        FHIROperationException exceptionWithIssue = buildExceptionWithIssue("The maximum number of resources "
                                + "allowed for the $everything operation (" + MAX_OVERALL_RESOURCES + ") has been exceeded "
                                + "for patient '" + logicalId + "'. Try using the bulkexport feature.", IssueType.TOO_COSTLY);
                        LOG.throwing(this.getClass().getName(), "doInvoke", exceptionWithIssue);
                        throw exceptionWithIssue;
                    }
                }
            }
        }

        Bundle.Builder bundleBuilder = Bundle.builder()
                .type(BundleType.SEARCHSET)
                .id(UUID.randomUUID().toString())
                .entry(allEntries)
                .total(UnsignedInt.of(allEntries.size()));

        Parameters outputParameters;
        try {
            outputParameters = FHIROperationUtil.getOutputParameters(bundleBuilder.build());
        } catch (Exception e) {
            FHIROperationException exceptionWithIssue = buildExceptionWithIssue("An unexpected error occurred while "
                    + "creating the operation output parameters for the resulting Bundle.", IssueType.EXCEPTION, e);
            LOG.throwing(this.getClass().getName(), "doInvoke", exceptionWithIssue);
            throw exceptionWithIssue;
        }
        LOG.exiting(this.getClass().getName(), "doInvoke", outputParameters);
        return outputParameters;
    }

    /**
     * Parse the parameters and turn them into a {@link MultivaluedMap} to pass to the search service
     *
     * @param parameters the operation parameters
     * @param maxPageSize the max page size
     * @return the {@link MultivaluedMap} for the search service built from the parameters
     * @implNote the {@code _type} parameter is processed elsewhere
     */
    protected MultivaluedMap<String, String> parseQueryParameters(Parameters parameters, int maxPageSize) {
        MultivaluedMap<String, String> queryParameters = new MultivaluedHashMap<>();
        Parameter countParameter = getParameter(parameters, SearchConstants.COUNT);
        if (countParameter != null) {
            LOG.fine("The `count` parameter is currently not supported by the $everything operation, it will be ignored.");
        }
        // We will try to grab all resources of a given type, in order to reduce the number of pages we will get the max page size
        queryParameters.add(SearchConstants.COUNT, maxPageSize + "");
        // We use gt/lt here in an effort to be more liberal in terms of what we return
        // https://ibm-watsonhealth.slack.com/archives/C14JTTR6C/p1614181836050500?thread_ts=1614180853.047300&cid=C14JTTR6C
        Parameter startParameter = getParameter(parameters, START_QUERY_PARAMETER);
        if (startParameter != null) {
            queryParameters.add(DATE_QUERY_PARAMETER, STARTING_FROM + startParameter.getValue().as(FHIR_DATE).getValue());
        }
        Parameter endParameter = getParameter(parameters, END_QUERY_PARAMETER);
        if (endParameter != null) {
            queryParameters.add(DATE_QUERY_PARAMETER, UP_UNTIL + endParameter.getValue().as(FHIR_DATE).getValue());
        }
        Parameter sinceParameter = getParameter(parameters, SINCE_QUERY_PARAMETER);
        if (sinceParameter != null) {
            queryParameters.add(LAST_UPDATED_QUERY_PARAMETER, STARTING_FROM + sinceParameter.getValue().as(FHIR_INSTANT).getValue());
        }
        return queryParameters;
    }

    /**
     * Get the list of resource types requested by the user via the _type parameter
     *
     * @param parameters the {@link Parameters} object
     * @return the list of patient subresources that will be included in the $everything operation, as provided by the user
     * @throws FHIRSearchException
     */
    protected List<String> getOverridenIncludedResourceTypes(Parameters parameters, List<String> defaultResourceTypes) throws FHIRSearchException {
        List<String> typeOverrides = new ArrayList<>();
        List<Parameter> typeParameters = getParameters(parameters, SearchConstants.RESOURCE_TYPE);
        if (typeParameters.isEmpty()) {
            return typeOverrides;
        }

        List<String> unknownTypes= new ArrayList<>();
        for (Parameter typesParameter : typeParameters) {
            String typeOverridesParam = typesParameter.getValue().as(FHIR_STRING).getValue();
            String[] typeOverridesList = typeOverridesParam.split(SearchConstants.JOIN_STR);
            for (String typeOverride : typeOverridesList) {
                if (!defaultResourceTypes.contains(typeOverride)) {
                    unknownTypes.add(typeOverride);
                } else {
                    typeOverrides.add(typeOverride.trim());
                }
            }
        }

        FHIRRequestContext requestContext = FHIRRequestContext.get();
        if (!unknownTypes.isEmpty()) {
            String msg = "The following resource types are not supported by this operation: " + String.join(",", unknownTypes);
            if (HTTPHandlingPreference.LENIENT.equals(requestContext.getHandlingPreference())) {
                LOG.fine(msg);
            } else {
                throw SearchExceptionUtil.buildNewInvalidSearchException(msg);
            }
        }

        return typeOverrides;
    }

    /**
     * @return the list of patient subresources that will be included in the $everything operation
     * @throws FHIRSearchException
     */
    private List<String> getDefaultIncludedResourceTypes(FHIRResourceHelpers resourceHelper) throws FHIRSearchException {
        List<String> resourceTypes = new ArrayList<>(compartmentHelper.getCompartmentResourceTypes(PATIENT));

        try {
            PropertyGroup resourcesGroup = FHIRConfigHelper.getPropertyGroup(FHIRConfiguration.PROPERTY_RESOURCES);
            ResourcesConfigAdapter configAdapter = new ResourcesConfigAdapter(resourcesGroup);
            Set<String> supportedResourceTypes = configAdapter.getSupportedResourceTypes(Interaction.SEARCH);

            if (LOG.isLoggable(Level.FINE)) {
                StringBuilder resourceTypeBuilder = new StringBuilder(supportedResourceTypes.size());
                resourceTypeBuilder.append("supportedResourceTypes are: ");
                for (String resourceType: supportedResourceTypes) {
                   resourceTypeBuilder.append(resourceType);
                   resourceTypeBuilder.append(" ");
                }
                LOG.fine(resourceTypeBuilder.toString());
            }

            resourceTypes.retainAll(supportedResourceTypes);
        } catch (Exception e) {
            FHIRSearchException exceptionWithIssue = new FHIRSearchException("There has been an error retrieving the list "
                    + "of supported resource types of the $everything operation.", e);
            LOG.throwing(this.getClass().getName(), "doInvoke", exceptionWithIssue);
            throw exceptionWithIssue;
        }

        if (LOG.isLoggable(Level.FINE)) {
            StringBuilder resourceTypeBuilder = new StringBuilder(resourceTypes.size());
            resourceTypeBuilder.append("resourceTypes are: ");
            for (String resourceType: resourceTypes) {
                resourceTypeBuilder.append(resourceType);
                resourceTypeBuilder.append(" ");
            }
            LOG.fine(resourceTypeBuilder.toString());
        }
        return resourceTypes;
    }

    private void addIncludesSearchParameters(String compartmentMemberType, MultivaluedMap<String, String> searchParameters,
            List<String> extraResources, SearchHelper searchHelper) throws Exception {
        // Add in Location, Medication, Organization, and Practitioner resources which are pointed to
        // from search parameters only if the request does not have a _type parameter or it does have a
        // _type parameter that includes these

        List<String> allowedIncludes = new ArrayList<String>(10);

        try {
            allowedIncludes = FHIRConfigHelper.getSearchPropertyRestrictions(compartmentMemberType, FHIRConfigHelper.SEARCH_PROPERTY_TYPE_INCLUDE);
        } catch (Exception e) {
            FHIRSearchException exceptionWithIssue = new FHIRSearchException("There has been an error retrieving the list of supported search parameters for the $everything operation.", e);
            LOG.throwing(this.getClass().getName(), "doInvoke", exceptionWithIssue);
            throw exceptionWithIssue;
        }

        // Add in _includes for all search parameters that are Location, Medication, Organization, or Practitioner
        if (compartmentMemberType.equals(ResourceType.Value.ADVERSE_EVENT.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "location", ResourceType.Value.LOCATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "recorder", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "subject", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "substance", ResourceType.Value.MEDICATION, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.ALLERGY_INTOLERANCE.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "recorder", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "asserter", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.APPOINTMENT.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "location", ResourceType.Value.LOCATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "practitioner", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.APPOINTMENT_RESPONSE.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "actor", ResourceType.Value.LOCATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "actor", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "location", ResourceType.Value.LOCATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "practitioner", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.ACCOUNT.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "owner", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "subject", ResourceType.Value.LOCATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "subject", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "subject", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.AUDIT_EVENT.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "agent", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "agent", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "source", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "source", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.BASIC.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "author", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "author", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.CARE_PLAN.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "performer", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "performer", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.CARE_TEAM.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "participant", ResourceType.Value.ORGANIZATION,searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "participant", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals("ChargeHistory")) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "service", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "enterer", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "performer-actor", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.CLAIM.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "care-team", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "care-team", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "enterer", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "facility", ResourceType.Value.LOCATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "insurer", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "payee", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "payee", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "provider", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "provider", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.CLAIM_RESPONSE.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "insurer", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "requestor", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "requestor", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.CLINICAL_IMPRESSION.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "assessor", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.COMMUNICATION.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "recipient", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "sender", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.COMPOSITION.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "attester", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "author", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.CONDITION.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "asserter", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.COMMUNICATION_REQUEST.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "recipient", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "recipient", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "requester", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "requester", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "sender", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "sender", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.CONSENT.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "actor", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "actor", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "consentor", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "consentor", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "organization", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.COVERAGE.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "payor", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "policy-holder", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.COVERAGE_ELIGIBILITY_REQUEST.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "enterer", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "facility", ResourceType.Value.LOCATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "provider", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "provider", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.COVERAGE_ELIGIBILITY_RESPONSE.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "insurer",ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "requestor", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "requestor", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.DETECTED_ISSUE.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "author", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.DEVICE_REQUEST.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "performer", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "performer", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "requester", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "requester", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "subject", ResourceType.Value.LOCATION, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.DIAGNOSTIC_REPORT.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "performer", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "performer", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "results-interpreter", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "results-interpreter", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "subject", ResourceType.Value.LOCATION, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.DOCUMENT_MANIFEST.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "author", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "author", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "recipient", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "recipient", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "subject", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.DOCUMENT_REFERENCE.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "authenticator", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "authenticator", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "author", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "author", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "custodian", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "subject", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.ENCOUNTER.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "location", ResourceType.Value.LOCATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "service-provider", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "participant", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.EPISODE_OF_CARE.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "care-manager", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "organization", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.EXPLANATION_OF_BENEFIT.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "care-team", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "care-team", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "enterer", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "facility", ResourceType.Value.LOCATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "insurer", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "payee", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "payee", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "provider", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "provider", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.FLAG.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "author", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "author", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "subject", ResourceType.Value.LOCATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "subject", ResourceType.Value.MEDICATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "subject", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "subject", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.GOAL.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "subject", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.GROUP.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "member", ResourceType.Value.MEDICATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "member", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "managing-entity", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "managing-entity", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.IMAGING_STUDY.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "interpreter", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "performer", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "referrer", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.IMMUNIZATION.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "location", ResourceType.Value.LOCATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "manufacturer", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "performer", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "performer", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.INVOICE.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "issuer", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "participant", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "participant", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "recipient", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.LIST.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "source", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "subject", ResourceType.Value.LOCATION, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.MEASURE_REPORT.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "reporter", ResourceType.Value.LOCATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "reporter", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "reporter", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "subject", ResourceType.Value.LOCATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "subject", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.MEDIA.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "operator", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "operator", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "subject", ResourceType.Value.LOCATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "subject", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.MEDICATION_ADMINISTRATION.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "medication", ResourceType.Value.MEDICATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "performer", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.MEDICATION_DISPENSE.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "destination", ResourceType.Value.LOCATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "medication", ResourceType.Value.MEDICATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "performer", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "receiver", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "responsibleparty", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.MEDICATION_REQUEST.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "intended-dispenser", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "intended-performer", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "intended-performer", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "medication", ResourceType.Value.MEDICATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "requester", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "requester", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.MEDICATION_STATEMENT.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "medication", ResourceType.Value.MEDICATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "source", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "source", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals("NutritionHistory")) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "provider", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.OBSERVATION.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "performer", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "performer", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "subject", ResourceType.Value.LOCATION, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.QUESTIONNAIRE_RESPONSE.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "author", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "author", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "source", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.PATIENT.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "general-practitioner", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "general-practitioner", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "organization", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.PERSON.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "organization", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "practitioner", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.PROCEDURE.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "location", ResourceType.Value.LOCATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "performer", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "performer", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.PROVENANCE.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "location", ResourceType.Value.LOCATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "agent", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "agent", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(Reference.class.getSimpleName())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "authenticator", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "authenticator", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "author", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "author", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "custodian", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "subject", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.REQUEST_GROUP.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "author", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "participant", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.RISK_ASSESSMENT.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "performer", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.SCHEDULE.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "actor", ResourceType.Value.LOCATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "actor", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.SERVICE_REQUEST.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "performer", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "performer", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "requester", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "requester", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "subject", ResourceType.Value.LOCATION, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.SPECIMEN.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "collector", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "subject", ResourceType.Value.LOCATION, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.SUPPLY_DELIVERY.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "receiver", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "supplier", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "supplier", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.SUPPLY_REQUEST.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "requester", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "requester", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "subject", ResourceType.Value.LOCATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "subject", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
            addSearchParameterIfNotExcluded(compartmentMemberType, "supplier", ResourceType.Value.ORGANIZATION, searchParameters, allowedIncludes, extraResources, searchHelper);
        } else if (compartmentMemberType.equals(ResourceType.Value.VISION_PRESCRIPTION.value())) {
            addSearchParameterIfNotExcluded(compartmentMemberType, "prescriber", ResourceType.Value.PRACTITIONER, searchParameters, allowedIncludes, extraResources, searchHelper);
        }

        // BodyStructure, ResearchSubject, and RelatedPerson have no references to:
        // Location, Medication, Organization, and Practitioner

        // DeviceUseStatement, EnrollmentRequest, FamilyMemberHistory, ChargeItem,
        // ImmunizationEvaluation, ImmunizationRecommendation, MolecularSequence,
        // and NutritionOrder have no search parameters that reference:
        // Location, Medication, Organization, and Practitioner
    }

    private void addSearchParameterIfNotExcluded(String compartmentMemberType, String code,
            ResourceType.Value subResourceType, MultivaluedMap<String, String> searchParameters,
            List<String> allowedIncludes, List<String> extraResources, SearchHelper searchHelper)
            throws Exception {
        // Need to make sure the search parameter has not been excluded
        String parameterName = compartmentMemberType + ":" + code + ":" + subResourceType.value();
        String simplifiedParameterName = compartmentMemberType + ":" + code;

        boolean isAllowed = allowedIncludes == null ||
                allowedIncludes.contains(parameterName) ||
                allowedIncludes.contains(simplifiedParameterName);

        boolean isConfigured = extraResources != null &&
                extraResources.contains(subResourceType.value());

        // Also check if the parameter is supported by retrieving it from SearchUtil
        SearchParameter searchParam = searchHelper.getSearchParameter(compartmentMemberType, code);

        if (isAllowed && isConfigured && searchParam != null) {
            searchParameters.add("_include", parameterName);
        } else {
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine("Filtering out searchParameter because it is not supported by the server config: " + parameterName);
            }
        }
    }

    /**
     * Read additional associated resources for the "extra" types (like location)
     * supported by this server configuration.  Only references of type LITERAL_RELATIVE
     * (including absolute references that match the server's hostname) are resolved by the
     * implementation.
     *
     * @param compartmentMemberType the type of resource currently drilling down on
     * @param newEntries a list of Bundle response entries that include compartmentMemberType instances found via search
     * @param allEntries a list of all entries that have been found so far (minus duplicates)
     * @param resourceHelper an instance of FHIRResourceHelpers to use to make the calls
     * @param extraResources a list of "extra" resource types configured for this server
     */
    private void readsOfAdditionalAssociatedResources(String compartmentMemberType, List<Entry> newEntries,
                List<Entry> allEntries, List<String> resourceIds,
                FHIRResourceHelpers resourceHelper, List<String> extraResources) throws Exception {
        if (extraResources == null || extraResources.isEmpty()) {
            return;
        }
        Bundle.Builder requestBundleBuilder = Bundle.builder().type(BundleType.BATCH);
        String url = ReferenceUtil.getServiceBaseUrl();

        for (Entry entry: newEntries) {
            // Look up entries by reference
            List<Reference> references = ReferenceFinder.getReferences(entry.getResource());
            for (Reference reference: references) {
                ReferenceValue referenceValue = ReferenceUtil.createReferenceValueFrom(reference, url);
                if (referenceValue.getType().equals(ReferenceValue.ReferenceType.LITERAL_RELATIVE)) {
                    String type = referenceValue.getTargetResourceType();
                    String value = referenceValue.getValue();
                    String resourceId = type + "/" + value;
                    if (!resourceIds.contains(resourceId) && extraResources.contains(type) && (type.equals(ResourceType.Value.LOCATION.value()) || type.equals(ResourceType.Value.MEDICATION.value())
                            || type.equals(ResourceType.Value.ORGANIZATION.value()) || type.equals(ResourceType.Value.PRACTITIONER.value()))) {
                        // Bundle up the requests so we can send them as a batch
                        addRequestToBundle(requestBundleBuilder, HTTPVerb.GET, resourceId, null);
                        resourceIds.add(resourceId);
                    }
                }
            }
        }

        Bundle requestBundle = requestBundleBuilder.build();
        // If we have resources to get, get them
        if (requestBundle.getEntry().size() > 0) {
            Bundle responseBundle = resourceHelper.doBundle(requestBundle, false);

            // Process the additional resources received
            for (Entry entry: responseBundle.getEntry()) {
                String type = ModelSupport.getTypeName(entry.getResource().getClass());
                // OperationOutcomes come back if the resource isn't found, so take them out
                if (!type.equals("OperationOutcome")) {
                    // remove this next line if fullUrls start getting set on batch reads
                    Bundle.Entry.Builder entryBuilder = entry.toBuilder()
                            .fullUrl(Uri.of(url + type + "/" + entry.getResource().getId()));
                    allEntries.add(entryBuilder.build());
                }
            }
        }
    }

    /**
     * Process the retrieved results to avoid duplicates and retrieve additional resources through references, if applicable
     *
     * @param results the resources returned from doSearch
     * @param compartmentMemberType the type of resource currently drilling down on
     * @param newEntries a list of entries of that compartment type that were found in a search
     * @param allEntries a list of all entries that have been found so far (minus duplicates)
     * @param resourceIds a list of all the resource ids already added to the results
     * @param resourceHelper an instance of FHIRResourceHelpers to use to make the calls
     * @param extraResources a list of "extra" resource types configured for this server
     * @param retrieveAdditionalResources whether the server config has specified to retrieve additional resources
     */
    private void processResults(Bundle results, String compartmentMemberType, List<Entry> newEntries,
                List<Entry> allEntries, List<String> resourceIds, FHIRResourceHelpers resourceHelper, List<String> extraResources,
                boolean retrieveAdditionalResources) throws Exception {
        // Only add resources we don't already have and keep track of what we've found so far,
        // otherwise, we were getting duplicate entries from the _includes
        for (Entry entry: results.getEntry()) {
            String externalIdentifier = ModelSupport.getTypeName(entry.getResource().getClass()) + "/" + entry.getResource().getId();
            if (!resourceIds.contains(externalIdentifier)) {
                resourceIds.add(externalIdentifier);
                allEntries.add(entry);
            }
        }
        if (retrieveAdditionalResources) {
            readsOfAdditionalAssociatedResources(compartmentMemberType, results.getEntry(), allEntries, resourceIds, resourceHelper, extraResources);
        }
    }

    //Copied from FHIRClientTest
    private void addRequestToBundle(Bundle.Builder bundleBuilder, HTTPVerb method, String url, Resource resource) throws Exception {

        Bundle.Entry.Request request = Bundle.Entry.Request.builder().method(method).url(Uri.of(url)).build();
        Bundle.Entry.Builder entryBuilder = Bundle.Entry.builder().request(request);

        if (resource != null) {
            entryBuilder.resource(resource);
        }

        bundleBuilder.entry(entryBuilder.build());
    }
}
