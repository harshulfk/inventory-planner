package fk.retail.ip.requirement.internal.command;

import com.google.inject.Inject;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.commons.collections4.map.MultiKeyMap;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalField;
import java.time.temporal.WeekFields;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.StreamingOutput;

import fk.retail.ip.core.poi.SpreadSheetWriter;
import fk.retail.ip.requirement.internal.entities.FsnBand;
import fk.retail.ip.requirement.internal.entities.Requirement;
import fk.retail.ip.requirement.internal.entities.WeeklySale;
import fk.retail.ip.requirement.internal.repository.FsnBandRepository;
import fk.retail.ip.requirement.internal.repository.WeeklySaleRepository;
import fk.retail.ip.requirement.model.RequirementDownloadLineItem;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;

/**
 * Created by nidhigupta.m on 26/01/17.
 */
public abstract class DownloadCommand {


   private Set<String> requirementFsns;
   private final FsnBandRepository fsnBandRepository;
   private final WeeklySaleRepository weeklySaleRepository;
   private List<RequirementDownloadLineItem> requirementDownloadLineItems;
   private Map<String, List<RequirementDownloadLineItem>> fsnToRequirement;


   @Inject
   public DownloadCommand(FsnBandRepository fsnBandRepository, WeeklySaleRepository weeklySaleRepository) {
       this.fsnBandRepository = fsnBandRepository;
       this.weeklySaleRepository = weeklySaleRepository;
   }

   public StreamingOutput execute(List<Requirement> requirements, boolean isLastAppSupplierRequired) {

       requirementDownloadLineItems = requirements.stream().map(RequirementDownloadLineItem::new).collect(toList());
       fsnToRequirement = requirementDownloadLineItems.stream().collect(groupingBy(RequirementDownloadLineItem::getFsn));
       requirementFsns = Collections.unmodifiableSet(fsnToRequirement.keySet());
       fetchProductData();
       fetchFsnBandData();
       fetchSalesBucketData();
       fetchRequirementStateData();
       if (isLastAppSupplierRequired) {
           fetchLastAppSupplierDataFromProc();
       }
       return generateExcel(requirementDownloadLineItems);

   }

    protected void fetchProductData() {

    }

    protected void fetchFsnBandData() {
        List<FsnBand> bands = fsnBandRepository.fetchBandDataForFSNs(requirementFsns);
        bands.stream().forEach(b -> {
            List<RequirementDownloadLineItem> items = fsnToRequirement.get(b.getFsn());
            items.forEach(i -> {
                i.setPvBand(b.getPvBand());
                i.setSalesBand(b.getSalesBand());
            });
        });
    }

    protected void fetchSalesBucketData() {
        List<WeeklySale> sales = weeklySaleRepository.fetchWeeklySalesForFsns(requirementFsns);

        requirementDownloadLineItems.forEach(reqItem
                        -> populateSalesData(sales, reqItem, reqItem::setWeek0Sale, reqItem::setWeek1Sale, reqItem::setWeek2Sale, reqItem::setWeek3Sale, reqItem::setWeek4Sale, reqItem::setWeek5Sale, reqItem::setWeek6Sale, reqItem::setWeek7Sale)
        );
    }

    protected void fetchLastAppSupplierDataFromProc() {

    }

    protected StreamingOutput generateExcel(List<RequirementDownloadLineItem> requirementDownloadLineItems) {
        SpreadSheetWriter spreadsheet = new SpreadSheetWriter();
        ObjectMapper mapper = new ObjectMapper();
        InputStream template = getClass().getResourceAsStream(getTemplateName());
        StreamingOutput output  = (OutputStream out) -> {
            try {
                spreadsheet.populateTemplate(template, out, mapper.convertValue(requirementDownloadLineItems, new TypeReference<List<Map>>() {
                }));
            } catch (InvalidFormatException e) {
                throw new WebApplicationException(e);
            }
        };
        return output;
    }

    private void populateSalesData(List<WeeklySale> sales, RequirementDownloadLineItem reqItem, Consumer<Integer>... setters) {
        MultiKeyMap<String, Integer> fsnWhWeekSalesMap = new MultiKeyMap();
        sales.forEach(s -> fsnWhWeekSalesMap.put(s.getFsn(), s.getWarehouse(), String.valueOf(s.getWeek()), s.getSaleQty()));
        LocalDate date = LocalDate.now();
        TemporalField weekOfYear = WeekFields.of(DayOfWeek.MONDAY, 1).weekOfWeekBasedYear();
        int currentWeek = date.get(weekOfYear);

        for (Consumer<Integer> setter : setters) {
            setter.accept(fsnWhWeekSalesMap.get(reqItem.getFsn(), reqItem.getWarehouse(), String.valueOf(currentWeek)));
            currentWeek = (currentWeek - 2 + 52) % 52 + 1;
        }
    }

    protected abstract String getTemplateName();

    abstract void fetchRequirementStateData();
}
