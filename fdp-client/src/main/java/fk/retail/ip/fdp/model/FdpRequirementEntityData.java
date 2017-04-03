package fk.retail.ip.fdp.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.dropwizard.jackson.JsonSnakeCase;
import lombok.Data;
import java.util.List;
import java.util.Date;

/**
 * Created by yogeshwari.k on 17/03/17.
 */
@Data
@JsonNaming(PropertyNamingStrategy.LowerCaseWithUnderscoresStrategy.class)
public class FdpRequirementEntityData {
    String requirementId;
    String partyId;
    String fsn;
    String warehouse;
    String forecast;
    double quantity;
    String supplier;
    int app;
    int mrp;
    String currency;
    Date requiredByDate;
    int inventoryQty;
    int pendingPoQty;
    int openReqQty;
    int iwitIntransitQty;
    String state;
    String poId;
    Boolean enabled;
    Date createdAt;
    Date updatedAt;
    List<String> policyIds;
}
