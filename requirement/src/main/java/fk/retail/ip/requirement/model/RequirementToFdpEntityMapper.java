package fk.retail.ip.requirement.model;

import com.google.common.collect.Lists;
import com.google.inject.Inject;
import fk.retail.ip.fdp.config.FdpConfiguration;
import fk.retail.ip.fdp.model.FdpEntityPayload;
import fk.retail.ip.fdp.model.FdpRequirementEntityData;
import fk.retail.ip.requirement.internal.entities.Requirement;
import org.joda.time.DateTime;
import java.util.List;

/**
 * Created by yogeshwari.k on 16/03/17.
 */
public class RequirementToFdpEntityMapper implements FdpEntityMapper<FdpRequirementEntityData,Requirement> {

    private final FdpConfiguration fdpConfiguration;

    @Inject
    public RequirementToFdpEntityMapper(FdpConfiguration fdpConfiguration){
        this.fdpConfiguration = fdpConfiguration;
    }

    @Override
    public FdpEntityPayload<FdpRequirementEntityData> convertToEntityPayload(Object requirementId, Requirement requirement) {
        FdpEntityPayload<FdpRequirementEntityData> fdpRequirementEntityPayload= new FdpEntityPayload();
        fdpRequirementEntityPayload.setEntityId(requirementId);
        fdpRequirementEntityPayload.setData(getRequirementEntityData(requirementId.toString(),requirement));
        fdpRequirementEntityPayload.setSchemaVersion(fdpConfiguration.getRequirementEntitySchemaVersion());
        fdpRequirementEntityPayload.setUpdatedAt(requirement.getUpdatedAt());
        return fdpRequirementEntityPayload;
    }

    private FdpRequirementEntityData getRequirementEntityData(String requirementId, Requirement requirement) {
        FdpRequirementEntityData fdpRequirementEntityData = new FdpRequirementEntityData();
        String partyId = "FKI";
        DateTime requiredBydate = DateTime.now();
        if(requirement.getSla()!=null)
            requiredBydate.plusDays(requirement.getSla());
        fdpRequirementEntityData.setRequirementId(requirementId);
        fdpRequirementEntityData.setPartyId(partyId);
        fdpRequirementEntityData.setFsn(requirement.getFsn());
        fdpRequirementEntityData.setWarehouse(requirement.getWarehouse());
        fdpRequirementEntityData.setForecast(requirement.getRequirementSnapshot().getForecast());
        fdpRequirementEntityData.setQuantity(requirement.getQuantity());
        fdpRequirementEntityData.setSupplier(requirement.getSupplier());
        fdpRequirementEntityData.setApp(requirement.getApp());
        fdpRequirementEntityData.setMrp(requirement.getMrp());
        fdpRequirementEntityData.setCurrency(requirement.getCurrency());
        fdpRequirementEntityData.setRequiredByDate(requiredBydate.toDate());
        fdpRequirementEntityData.setInventoryQty(requirement.getRequirementSnapshot().getInventoryQty());
        fdpRequirementEntityData.setPendingPoQty(requirement.getRequirementSnapshot().getPendingPoQty());
        fdpRequirementEntityData.setOpenReqQty(requirement.getRequirementSnapshot().getOpenReqQty());
        fdpRequirementEntityData.setIwitIntransitQty(requirement.getRequirementSnapshot().getIwitIntransitQty());
        fdpRequirementEntityData.setState(requirement.getState());
        fdpRequirementEntityData.setPoId(null);
        fdpRequirementEntityData.setEnabled(requirement.isEnabled());
        fdpRequirementEntityData.setCreatedAt(requirement.getCreatedAt());
        fdpRequirementEntityData.setUpdatedAt(requirement.getUpdatedAt());
        fdpRequirementEntityData.setPolicyIds(getPolicyIds(requirement.getRequirementSnapshot().getPolicyIds()));
        return fdpRequirementEntityData;
    }

    private List<String> getPolicyIds(String policyIds) {
        if (policyIds==null)
            return Lists.newArrayList();
        String[] policyIdArray = policyIds.split(",");
        //List<String> policyIdList = Arrays.asList(policyIds);
        List<String> policyIdList = Lists.newArrayList();
        for (String s : policyIdArray) {
            policyIdList.add(s);
        }
        return  policyIdList;
    }
}
