package fk.retail.ip.requirement.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.google.inject.Provider;
import fk.retail.ip.core.poi.SpreadSheetReader;
import fk.retail.ip.d42.client.D42Client;
import fk.retail.ip.requirement.internal.Constants;
import fk.retail.ip.requirement.internal.command.*;

import fk.retail.ip.requirement.internal.entities.Requirement;
import fk.retail.ip.requirement.internal.enums.OverrideStatus;
import fk.retail.ip.requirement.internal.enums.RequirementApprovalAction;
import fk.retail.ip.requirement.internal.factory.RequirementStateFactory;
import fk.retail.ip.requirement.internal.repository.RequirementApprovalTransitionRepository;
import fk.retail.ip.requirement.internal.repository.RequirementRepository;
import fk.retail.ip.requirement.internal.states.RequirementState;
import fk.retail.ip.requirement.model.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.json.JSONException;

import javax.ws.rs.core.StreamingOutput;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author nidhigupta.m
 * @author Pragalathan M <pragalathan.m@flipkart.com>
 * @author contradiction154
 */
@Slf4j
public class RequirementService {

    private final RequirementRepository requirementRepository;
    private final RequirementApprovalTransitionRepository requirementApprovalStateTransitionRepository;
    private final RequirementStateFactory requirementStateFactory;
    private final ApprovalService approvalService;
    private final Provider<CalculateRequirementCommand> calculateRequirementCommandProvider;
    private final SearchFilterCommand searchFilterCommand;
    private final Provider<SearchCommand> searchCommandProvider;
    private final FdpRequirementIngestorImpl fdpRequirementIngestor;
    private final D42Client d42Client;
    private final int PAGE_SIZE = 20;
    private final String BUCKET_NAME = "ip_requirements";

    @Inject
    public RequirementService(RequirementRepository requirementRepository, RequirementStateFactory requirementStateFactory,
                              ApprovalService approvalService, Provider<CalculateRequirementCommand> calculateRequirementCommandProvider,
                              RequirementApprovalTransitionRepository requirementApprovalStateTransitionRepository,
                              SearchFilterCommand searchFilterCommand, Provider<SearchCommand> searchCommandProvider, 
                              FdpRequirementIngestorImpl fdpRequirementIngestor, D42Client d42Client) {
        this.requirementRepository = requirementRepository;
        this.requirementStateFactory = requirementStateFactory;
        this.approvalService = approvalService;
        this.calculateRequirementCommandProvider = calculateRequirementCommandProvider;
        this.requirementApprovalStateTransitionRepository = requirementApprovalStateTransitionRepository;
        this.searchFilterCommand = searchFilterCommand;
        this.searchCommandProvider = searchCommandProvider;
        this.fdpRequirementIngestor = fdpRequirementIngestor;
        this.d42Client = d42Client;
    }

    public StreamingOutput downloadRequirement(DownloadRequirementRequest downloadRequirementRequest) {
        List<Long> requirementIds = downloadRequirementRequest.getRequirementIds();
        String requirementState = downloadRequirementRequest.getState();
        Map<String, Object> filters = downloadRequirementRequest.getFilters();
        boolean isLastAppSupplierRequired = downloadRequirementRequest.isLastAppSupplierRequired();
        List<String> fsns = searchFilterCommand.getSearchFilterFsns(filters);
        List<Requirement> requirements = requirementRepository.findRequirements(requirementIds, requirementState, fsns);
        requirements = requirements.stream().filter(requirement -> !requirement.getWarehouse().equals("all")).collect(Collectors.toList());
        RequirementState state = requirementStateFactory.getRequirementState(requirementState);
        return state.download(requirements, isLastAppSupplierRequired);
    }

    public UploadResponse uploadRequirement(
            InputStream inputStream,
            FormDataContentDisposition fileDetail,
            FormDataBodyPart formBody,
            String requirementState,
            String userId
    ) throws IOException, InvalidFormatException {
        ByteArrayOutputStream baos  = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = inputStream.read(buffer)) > -1 ) {
            baos.write(buffer, 0, len);
        }
        baos.flush();
        String fileName = fileDetail.getFileName();
        String timeStamp = new SimpleDateFormat("yyyy.MM.dd.HH.mm.ss").format(new Date());
        String objectKey = String.format("%s:%s:%s", timeStamp, userId, fileName);
        String contentType = formBody.getMediaType().toString();

        d42Client.put(BUCKET_NAME, objectKey, new ByteArrayInputStream(baos.toByteArray()), contentType);

        SpreadSheetReader spreadSheetReader = new SpreadSheetReader();
        List<Map<String, Object>> parsedMappingList = spreadSheetReader.read(new ByteArrayInputStream(baos.toByteArray()));

        log.info("Uploaded file parsed and contains " + parsedMappingList.size() +  " records");

        if (parsedMappingList.size() == 0) {
            UploadResponse uploadResponse = new UploadResponse();
            uploadResponse.setStatus(Constants.EMPTY_RECORDS);
            uploadResponse.setSuccessfulRowCount(0);
            return uploadResponse;
        }
        ObjectMapper mapper = new ObjectMapper();

        try {
            List<RequirementDownloadLineItem> requirementDownloadLineItems = mapper.convertValue(parsedMappingList,
                    new TypeReference<List<RequirementDownloadLineItem>>() {});
            List<Requirement> requirements;
            List<Long> requirementIds = new ArrayList<>();
            requirementDownloadLineItems.forEach(row ->
                            requirementIds.add(row.getRequirementId())
            );

            requirements = requirementRepository.findActiveRequirementForState(requirementIds, requirementState);
            log.info("number of requirements found for uploaded records : " + requirements.size());

            if (requirements.size() == 0) {
                UploadResponse uploadResponse = new UploadResponse();
                uploadResponse.setStatus(Constants.NO_REQUIREMENT_FOUND);
                uploadResponse.setSuccessfulRowCount(0);
                return uploadResponse;
            } else {
                RequirementState state = requirementStateFactory.getRequirementState(requirementState);
                try {
                    List<UploadOverrideFailureLineItem> uploadLineItems = state.upload(requirements, requirementDownloadLineItems, userId);
                    int successfulRowCount = requirementDownloadLineItems.size() - uploadLineItems.size();
                    UploadResponse uploadResponse = new UploadResponse();
                    uploadResponse.setUploadOverrideFailureLineItems(uploadLineItems);
                    uploadResponse.setSuccessfulRowCount(successfulRowCount);
                    if (uploadLineItems.isEmpty()) {
                        uploadResponse.setStatus(OverrideStatus.SUCCESS.toString());
                    } else {
                        uploadResponse.setStatus(OverrideStatus.FAILURE.toString());
                    }
                    return uploadResponse;

                } catch(UnsupportedOperationException ex) {
                    log.error("Unsupported operation");
                    UploadResponse uploadResponse = new UploadResponse();
                    uploadResponse.setStatus(Constants.UNSUPPORTED_OPERATION);
                    uploadResponse.setSuccessfulRowCount(0);
                    return uploadResponse;
                }

            }
        } catch(IllegalArgumentException ex) {
            log.warn("One or more fields were unrecognised", ex.getStackTrace());
            UploadResponse uploadResponse = new UploadResponse();
            uploadResponse.setStatus(Constants.UNKNOWN_COLUMN);
            uploadResponse.setSuccessfulRowCount(0);
            return uploadResponse;
        }

    }

    public String changeState(RequirementApprovalRequest request, String userId) throws JSONException {
        log.info("Approval request received for " + request);
        RequirementApprovalAction action = RequirementApprovalAction.valueOf(request.getFilters().get("projection_action").toString());
        boolean forward = action.isForward();
        List<Long> ids = (List<Long>) request.getFilters().get("id");
        String state = (String) request.getFilters().get("state");
        Function<Requirement, String> getter = Requirement::getState;
        List<Requirement> requirements;
        List<String> fsns = searchFilterCommand.getSearchFilterFsns(request.getFilters());
        requirements = requirementRepository.findRequirements(ids, state, fsns);
        log.info("Change state Request for {} number of requirements", requirements.size());
        approvalService.changeState(requirements, state, userId, forward, getter, new ApprovalService.CopyOnStateChangeAction(requirementRepository, requirementApprovalStateTransitionRepository, fdpRequirementIngestor));
        log.info("State changed for {} number of requirements", requirements.size());
        return "{\"msg\":\"Moved " + requirements.size() + " requirements to new state.\"}";
    }

    public SearchResponse.GroupedResponse search(RequirementSearchRequest request) throws JSONException {
        log.info("Search Requirement request received " + request);
        Integer pageNo;
        List<Requirement> requirements;
        List<Long> projectionIds;
        int startIndex, endIndex;
        List<Long> batchProjectionIds;
        pageNo = request.getFilters().get("page")!=null ? Integer.parseInt(request.getFilters().get("page").toString()): 1;
        String state = (String) request.getFilters().get("state");
        List<String> fsns = searchFilterCommand.getSearchFilterFsns(request.getFilters());
        if(fsns == null || fsns.isEmpty()) return new SearchResponse.GroupedResponse(0, PAGE_SIZE);
        projectionIds = requirementRepository.findProjectionIds(fsns, state);
        log.info("Search Request for {} number of ProjectionIds", projectionIds.size());
        if(projectionIds==null || projectionIds.isEmpty()) return new SearchResponse.GroupedResponse(0, PAGE_SIZE);
        startIndex = (pageNo-1)*PAGE_SIZE;
        endIndex = (projectionIds.size() >= pageNo*PAGE_SIZE) ? (pageNo*PAGE_SIZE) : projectionIds.size();
        batchProjectionIds = projectionIds.subList(startIndex, endIndex);
        requirements = requirementRepository.findRequirements(batchProjectionIds, state, Lists.newArrayList());
        log.info("Search Request for {} number of requirements", requirements.size());
        Map<String, List<RequirementSearchLineItem>> fsnToSearchItemsMap =  searchCommandProvider.get().execute(requirements);
        log.info("Search Request for {} number of fsns", fsnToSearchItemsMap.size());
        SearchResponse.GroupedResponse groupedResponse = new SearchResponse.GroupedResponse(projectionIds.size(), PAGE_SIZE);
        for (String fsn : fsnToSearchItemsMap.keySet()) {
            SearchResponse searchResponse = new SearchResponse(fsnToSearchItemsMap.get(fsn));
            groupedResponse.getProjections().add(searchResponse);
        }
        log.info("Got Search Response for Requirement");
        return groupedResponse;
    }


    public void calculateRequirement(CalculateRequirementRequest calculateRequirementRequest) {
        calculateRequirementCommandProvider.get().withFsns(calculateRequirementRequest.getFsns()).execute();
    }
}
