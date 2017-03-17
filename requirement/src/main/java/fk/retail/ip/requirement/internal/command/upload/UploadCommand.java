package fk.retail.ip.requirement.internal.command.upload;


import fk.retail.ip.requirement.internal.Constants;
import fk.retail.ip.requirement.internal.entities.Requirement;
import fk.retail.ip.requirement.internal.enums.OverrideKeys;
import fk.retail.ip.requirement.internal.enums.OverrideStatus;
import fk.retail.ip.requirement.internal.repository.RequirementRepository;
import fk.retail.ip.requirement.model.RequirementDownloadLineItem;
import fk.retail.ip.requirement.model.RequirementUploadLineItem;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Created by vaibhav.agarwal on 03/02/17.
 * Code never lies.
*/
@Slf4j
public abstract class UploadCommand {

    abstract Map<String, Object> validateAndSetStateSpecificFields(RequirementDownloadLineItem row);

    private final RequirementRepository requirementRepository;

    public UploadCommand(RequirementRepository requirementRepository) {
        this.requirementRepository = requirementRepository;
    }

    public List<RequirementUploadLineItem> execute(
            List<RequirementDownloadLineItem> requirementDownloadLineItems, List<Requirement> requirements
    ) {

        Map<Long, Requirement> requirementMap = requirements.stream().
                collect(Collectors.toMap(Requirement::getId, Function.identity()));

        ArrayList<RequirementUploadLineItem> requirementUploadLineItems = new ArrayList<>();
        int rowCount = 0;
        for(RequirementDownloadLineItem row : requirementDownloadLineItems) {
            RequirementUploadLineItem requirementUploadLineItem = new RequirementUploadLineItem();
            rowCount += 1;
            String fsn = row.getFsn();
            String warehouse = row.getWarehouseName();

            Optional<String> genericComment = validateGenericColumns(fsn, warehouse);

            if (genericComment.isPresent()) {
                requirementUploadLineItem.setFailureReason(genericComment.get());
                requirementUploadLineItem.setFsn(fsn == null ? "" : fsn);
                requirementUploadLineItem.setWarehouse(warehouse == null ? "" : warehouse);
                requirementUploadLineItem.setRowNumber(rowCount);
                requirementUploadLineItems.add(requirementUploadLineItem);

            } else {

                Map<String, Object> overriddenValues = validateAndSetStateSpecificFields(row);
                String status = overriddenValues.get(Constants.STATUS).toString();
                OverrideStatus overrideStatus = OverrideStatus.fromString(status);

                switch(overrideStatus) {
                    case FAILURE:
                        requirementUploadLineItem.setFailureReason(overriddenValues.get
                                (OverrideKeys.OVERRIDE_COMMENT.toString()).toString());
                        requirementUploadLineItem.setFsn(fsn);
                        requirementUploadLineItem.setRowNumber(rowCount);
                        requirementUploadLineItem.setWarehouse(warehouse);
                        requirementUploadLineItems.add(requirementUploadLineItem);
                        break;

                    case UPDATE:
                        Long requirementId = row.getRequirementId();

                        if (requirementMap.containsKey(requirementId)) {
                            Requirement requirement = requirementMap.get(requirementId);

                            if (overriddenValues.containsKey(OverrideKeys.QUANTITY.toString())) {
                                requirement.setQuantity
                                        ((Integer) overriddenValues.get(OverrideKeys.QUANTITY.toString()));
                            }

                            if (overriddenValues.containsKey(OverrideKeys.SLA.toString())) {
                                requirement.setSla((Integer) overriddenValues.get(OverrideKeys.SLA.toString()));
                            }

                            if (overriddenValues.containsKey(OverrideKeys.APP.toString())) {
                                requirement.setApp((Integer) overriddenValues.get(OverrideKeys.APP.toString()));
                            }

                            if (overriddenValues.containsKey(OverrideKeys.SUPPLIER.toString())) {
                                requirement.setSupplier
                                        (overriddenValues.get(OverrideKeys.SUPPLIER.toString()).toString());
                            }

                            if (overriddenValues.containsKey(OverrideKeys.OVERRIDE_COMMENT.toString())) {
                                requirement.setOverrideComment
                                        (overriddenValues.get(OverrideKeys.OVERRIDE_COMMENT.toString()).toString());
                            }

                        } else {
                            requirementUploadLineItem.setFailureReason
                                    (Constants.REQUIREMENT_NOT_FOUND_FOR_GIVEN_REQUIREMENT_ID);
                            requirementUploadLineItem.setFsn(fsn);
                            requirementUploadLineItem.setRowNumber(rowCount);
                            requirementUploadLineItem.setWarehouse(warehouse);
                            requirementUploadLineItems.add(requirementUploadLineItem);
                        }
                        break;

                    case SUCCESS:
                        break;

                    }

                }
            }

        return requirementUploadLineItems;
    }

    private Optional<String> validateGenericColumns(String fsn, String warehouse){
        String genericValidationComment;
        if (fsn == null || warehouse == null) {
           genericValidationComment =  Constants.FSN_OR_WAREHOUSE_IS_MISSING;
            return Optional.of(genericValidationComment);
        }
        return Optional.empty();
    }

    protected Optional<String> validateQuantityOverride(
            Integer currentQuantity, Integer suggestedQuantity, String overrideComment) {
        String validationComment;
        if (suggestedQuantity == null) {
            return Optional.empty();
        }
        if (suggestedQuantity <= 0) {
            validationComment = isEmptyString(overrideComment) ?
                    Constants.INVALID_QUANTITY_WITHOUT_COMMENT :
                    Constants.SUGGESTED_QUANTITY_IS_NOT_GREATER_THAN_ZERO;
            return Optional.of(validationComment);
        } else if (suggestedQuantity != currentQuantity && isEmptyString(overrideComment)) {
            validationComment = Constants.QUANTITY_OVERRIDE_COMMENT_IS_MISSING;
            return Optional.of(validationComment);
        } else {
            return Optional.empty();
        }
    }

    protected boolean isEmptyString(String comment) {
        return true ? comment == null || comment.trim().isEmpty() : false;
    }

}
