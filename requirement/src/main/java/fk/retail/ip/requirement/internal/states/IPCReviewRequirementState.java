package fk.retail.ip.requirement.internal.states;

import com.google.inject.Inject;
import com.google.inject.Provider;
import fk.retail.ip.requirement.internal.command.download.DownloadIPCReviewCommand;
import fk.retail.ip.requirement.internal.entities.Requirement;
import fk.retail.ip.requirement.model.RequirementDownloadLineItem;
import fk.retail.ip.requirement.model.UploadOverrideFailureLineItem;

import java.util.List;
import javax.ws.rs.core.StreamingOutput;

/**
 * Created by nidhigupta.m on 21/02/17.
 */
public class IPCReviewRequirementState implements RequirementState {
    private final Provider<DownloadIPCReviewCommand> downloadIPCReviewCommandProvider;

    @Inject
    public IPCReviewRequirementState(Provider<DownloadIPCReviewCommand> downloadIPCReviewCommandProvider) {
        this.downloadIPCReviewCommandProvider = downloadIPCReviewCommandProvider;
    }

    @Override
    public StreamingOutput download(List<Requirement> requirements, boolean isLastAppSupplierRequired) {
        return downloadIPCReviewCommandProvider.get().execute(requirements, isLastAppSupplierRequired);
    }

    @Override
    public List<UploadOverrideFailureLineItem> upload(List<Requirement> requirements,
                                                      List<RequirementDownloadLineItem> parsedJson,
                                                      String userId) {
        throw new UnsupportedOperationException("Invalid Operation");
    }
}

