/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.desktop.main.dao.economy.supply;

import bisq.desktop.common.view.ActivatableView;
import bisq.desktop.common.view.FxmlView;
import bisq.desktop.components.TitledGroupBg;
import bisq.desktop.util.AxisInlierUtils;
import bisq.desktop.util.Layout;
import bisq.desktop.util.MovingAverageUtils;

import bisq.core.dao.DaoFacade;
import bisq.core.dao.state.DaoStateListener;
import bisq.core.dao.state.DaoStateService;
import bisq.core.dao.state.model.blockchain.Block;
import bisq.core.dao.state.model.blockchain.Tx;
import bisq.core.dao.state.model.governance.Issuance;
import bisq.core.dao.state.model.governance.IssuanceType;
import bisq.core.locale.GlobalSettings;
import bisq.core.locale.Res;
import bisq.core.util.coin.BsqFormatter;

import bisq.common.util.Tuple3;

import org.bitcoinj.core.Coin;

import javax.inject.Inject;

import javafx.scene.Node;
import javafx.scene.chart.AreaChart;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;

import javafx.geometry.Insets;
import javafx.geometry.Side;

import javafx.collections.ListChangeListener;

import javafx.util.StringConverter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjuster;
import java.time.temporal.TemporalAdjusters;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Spliterators.AbstractSpliterator;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static bisq.desktop.util.FormBuilder.addSlideToggleButton;
import static bisq.desktop.util.FormBuilder.addTitledGroupBg;
import static bisq.desktop.util.FormBuilder.addTopLabelReadOnlyTextField;



import java.sql.Date;

@FxmlView
public class SupplyView extends ActivatableView<GridPane, Void> implements DaoStateListener {

    private static final String MONTH = "month";
    private static final String DAY = "day";

    private final DaoFacade daoFacade;
    private DaoStateService daoStateService;
    private final BsqFormatter bsqFormatter;

    private int gridRow = 0;
    private TextField genesisIssueAmountTextField, compRequestIssueAmountTextField, reimbursementAmountTextField,
            totalBurntFeeAmountTextField, totalLockedUpAmountTextField, totalUnlockingAmountTextField,
            totalUnlockedAmountTextField, totalConfiscatedAmountTextField, totalAmountOfInvalidatedBsqTextField;
    private XYChart.Series<Number, Number> seriesBSQIssued, seriesBSQBurnt, seriesBSQBurntMA;
    private ListChangeListener<XYChart.Data<Number, Number>> changeListenerBSQBurnt;
    private NumberAxis yAxisBSQBurnt;

    private ToggleButton zoomToInliersSlide;
    private boolean isZoomingToInliers = false;

    // Parameters for zooming to inliers; explanations in AxisInlierUtils.
    private int chartMaxNumberOfTicks = 10;
    private double chartPercentToTrim = 5;
    private double chartHowManyStdDevsConstituteOutlier = 10;

    private static final Map<String, TemporalAdjuster> ADJUSTERS = new HashMap<>();


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    private SupplyView(DaoFacade daoFacade,
                       DaoStateService daoStateService,
                       BsqFormatter bsqFormatter) {
        this.daoFacade = daoFacade;
        this.daoStateService = daoStateService;
        this.bsqFormatter = bsqFormatter;
    }

    @Override
    public void initialize() {
        ADJUSTERS.put(MONTH, TemporalAdjusters.firstDayOfMonth());
        ADJUSTERS.put(DAY, TemporalAdjusters.ofDateAdjuster(d -> d));

        createSupplyIncreasedInformation();
        createSupplyReducedInformation();

        createSupplyLockedInformation();
    }

    @Override
    protected void activate() {
        daoFacade.addBsqStateListener(this);

        if (isZoomingToInliers) {
            activateZoomingToInliers();
        }

        updateWithBsqBlockChainData();

        activateButtons();
    }

    @Override
    protected void deactivate() {
        daoFacade.removeBsqStateListener(this);

        deactivateZoomingToInliers();

        deactivateButtons();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // DaoStateListener
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onParseBlockCompleteAfterBatchProcessing(Block block) {
        updateWithBsqBlockChainData();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void createSupplyIncreasedInformation() {
        addTitledGroupBg(root, ++gridRow, 3, Res.get("dao.factsAndFigures.supply.issued"));

        Tuple3<Label, TextField, VBox> genesisAmountTuple = addTopLabelReadOnlyTextField(root, gridRow,
                Res.get("dao.factsAndFigures.supply.genesisIssueAmount"), Layout.FIRST_ROW_DISTANCE);
        genesisIssueAmountTextField = genesisAmountTuple.second;
        GridPane.setColumnSpan(genesisAmountTuple.third, 2);

        compRequestIssueAmountTextField = addTopLabelReadOnlyTextField(root, ++gridRow,
                Res.get("dao.factsAndFigures.supply.compRequestIssueAmount")).second;
        reimbursementAmountTextField = addTopLabelReadOnlyTextField(root, gridRow, 1,
                Res.get("dao.factsAndFigures.supply.reimbursementAmount")).second;


        seriesBSQIssued = new XYChart.Series<>();

        var chart = createBSQIssuedChart(seriesBSQIssued);

        var chartPane = wrapInChartPane(chart);
        root.getChildren().add(chartPane);
    }

    private void createSupplyReducedInformation() {
        addTitledGroupBg(root, ++gridRow, 2, Res.get("dao.factsAndFigures.supply.burnt"), Layout.GROUP_DISTANCE);

        totalBurntFeeAmountTextField = addTopLabelReadOnlyTextField(root, gridRow,
                Res.get("dao.factsAndFigures.supply.burntAmount"), Layout.FIRST_ROW_AND_GROUP_DISTANCE).second;
        totalAmountOfInvalidatedBsqTextField = addTopLabelReadOnlyTextField(root, gridRow, 1,
                Res.get("dao.factsAndFigures.supply.invalidTxs"), Layout.FIRST_ROW_AND_GROUP_DISTANCE).second;

        var buttonTitle = Res.get("dao.factsAndFigures.supply.burntZoomToInliers");
        zoomToInliersSlide = addSlideToggleButton(root, ++gridRow, buttonTitle);

        seriesBSQBurnt = new XYChart.Series<>();
        seriesBSQBurntMA = new XYChart.Series<>();

        var chart = createBSQBurntChart(seriesBSQBurnt, seriesBSQBurntMA);

        var chartPane = wrapInChartPane(chart);
        root.getChildren().add(chartPane);
    }

    private void createSupplyLockedInformation() {
        TitledGroupBg titledGroupBg = addTitledGroupBg(root, ++gridRow, 2, Res.get("dao.factsAndFigures.supply.locked"), Layout.GROUP_DISTANCE);
        titledGroupBg.getStyleClass().add("last");

        totalLockedUpAmountTextField = addTopLabelReadOnlyTextField(root, gridRow,
                Res.get("dao.factsAndFigures.supply.totalLockedUpAmount"),
                Layout.FIRST_ROW_AND_GROUP_DISTANCE).second;
        totalUnlockingAmountTextField = addTopLabelReadOnlyTextField(root, gridRow, 1,
                Res.get("dao.factsAndFigures.supply.totalUnlockingAmount"),
                Layout.FIRST_ROW_AND_GROUP_DISTANCE).second;

        totalUnlockedAmountTextField = addTopLabelReadOnlyTextField(root, ++gridRow,
                Res.get("dao.factsAndFigures.supply.totalUnlockedAmount")).second;
        totalConfiscatedAmountTextField = addTopLabelReadOnlyTextField(root, gridRow, 1,
                Res.get("dao.factsAndFigures.supply.totalConfiscatedAmount")).second;
    }

    private Node createBSQIssuedChart(XYChart.Series<Number, Number> series) {
        NumberAxis xAxis = new NumberAxis();
        configureAxis(xAxis);
        xAxis.setTickLabelFormatter(getTimestampTickLabelFormatter("MMM uu"));

        NumberAxis yAxis = new NumberAxis();
        configureYAxis(yAxis);
        yAxis.setTickLabelFormatter(BSQPriceTickLabelFormatter);

        AreaChart<Number, Number> chart = new AreaChart<>(xAxis, yAxis);
        configureChart(chart);

        series.setName(Res.get("dao.factsAndFigures.supply.issued"));
        chart.getData().add(series);

        return chart;
    }

    @SuppressWarnings("unchecked")
    private Node createBSQBurntChart(
            XYChart.Series<Number, Number> seriesBSQBurnt,
            XYChart.Series<Number, Number> seriesBSQBurntMA
    ) {
        Supplier<NumberAxis> makeXAxis = () -> {
            NumberAxis xAxis = new NumberAxis();
            configureAxis(xAxis);
            xAxis.setTickLabelFormatter(getTimestampTickLabelFormatter("d MMM"));
            return xAxis;
        };

        Supplier<NumberAxis> makeYAxis = () -> {
            NumberAxis yAxis = new NumberAxis();
            configureYAxis(yAxis);
            yAxis.setTickLabelFormatter(BSQPriceTickLabelFormatter);
            return yAxis;
        };

        seriesBSQBurnt.setName(Res.get("dao.factsAndFigures.supply.burnt"));

        var burntMALabel = Res.get("dao.factsAndFigures.supply.burntMovingAverage");
        seriesBSQBurntMA.setName(burntMALabel);

        var yAxis = makeYAxis.get();
        initializeChangeListener(yAxis);

        var chart = new LineChart<>(makeXAxis.get(), yAxis);

        chart.getData().addAll(seriesBSQBurnt, seriesBSQBurntMA);

        configureChart(chart);
        chart.setCreateSymbols(false);
        chart.setLegendVisible(true);

        return chart;
    }

    private void initializeChangeListener(NumberAxis axis) {
        // Keep a class-scope reference. Needed for switching between inliers-only and full chart.
        yAxisBSQBurnt = axis;

        changeListenerBSQBurnt = AxisInlierUtils.getListenerThatZoomsToInliers(
                yAxisBSQBurnt, chartMaxNumberOfTicks, chartPercentToTrim, chartHowManyStdDevsConstituteOutlier);
    }

    private void configureYAxis(NumberAxis axis) {
        configureAxis(axis);

        axis.setForceZeroInRange(true);
        axis.setTickLabelGap(5);
        axis.setSide(Side.RIGHT);
    }

    private void configureAxis(NumberAxis axis) {
        axis.setForceZeroInRange(false);
        axis.setAutoRanging(true);
        axis.setTickMarkVisible(false);
        axis.setMinorTickVisible(false);
        axis.setTickLabelGap(6);
    }

    private StringConverter<Number> getTimestampTickLabelFormatter(String datePattern) {
        return new StringConverter<>() {
            @Override
            public String toString(Number timestamp) {
                LocalDateTime localDateTime = LocalDateTime.ofEpochSecond(timestamp.longValue(),
                        0, OffsetDateTime.now(ZoneId.systemDefault()).getOffset());
                return localDateTime.format(DateTimeFormatter.ofPattern(datePattern, GlobalSettings.getLocale()));
            }

            @Override
            public Number fromString(String string) {
                return 0;
            }
        };
    }

    private StringConverter<Number> BSQPriceTickLabelFormatter =
            new StringConverter<>() {
                @Override
                public String toString(Number marketPrice) {
                    return bsqFormatter.formatBSQSatoshisWithCode(marketPrice.longValue());
                }

                @Override
                public Number fromString(String string) {
                    return 0;
                }
            };

    private <X, Y> void configureChart(XYChart<X, Y> chart) {
        chart.setLegendVisible(false);
        chart.setAnimated(false);
        chart.setId("charts-dao");

        chart.setMinHeight(300);
        chart.setPrefHeight(300);
        chart.setPadding(new Insets(0));
    }

    private Pane wrapInChartPane(Node child) {
        AnchorPane chartPane = new AnchorPane();
        chartPane.getStyleClass().add("chart-pane");

        AnchorPane.setTopAnchor(child, 15d);
        AnchorPane.setBottomAnchor(child, 10d);
        AnchorPane.setLeftAnchor(child, 25d);
        AnchorPane.setRightAnchor(child, 10d);

        chartPane.getChildren().add(child);

        GridPane.setColumnSpan(chartPane, 2);
        GridPane.setRowIndex(chartPane, ++gridRow);
        GridPane.setMargin(chartPane, new Insets(10, 0, 0, 0));

        return chartPane;
    }

    private void updateWithBsqBlockChainData() {
        Coin issuedAmountFromGenesis = daoFacade.getGenesisTotalSupply();
        genesisIssueAmountTextField.setText(bsqFormatter.formatAmountWithGroupSeparatorAndCode(issuedAmountFromGenesis));

        Coin issuedAmountFromCompRequests = Coin.valueOf(daoFacade.getTotalIssuedAmount(IssuanceType.COMPENSATION));
        compRequestIssueAmountTextField.setText(bsqFormatter.formatAmountWithGroupSeparatorAndCode(issuedAmountFromCompRequests));
        Coin issuedAmountFromReimbursementRequests = Coin.valueOf(daoFacade.getTotalIssuedAmount(IssuanceType.REIMBURSEMENT));
        reimbursementAmountTextField.setText(bsqFormatter.formatAmountWithGroupSeparatorAndCode(issuedAmountFromReimbursementRequests));

        Coin totalBurntFee = Coin.valueOf(daoFacade.getTotalBurntFee());
        Coin totalLockedUpAmount = Coin.valueOf(daoFacade.getTotalLockupAmount());
        Coin totalUnlockingAmount = Coin.valueOf(daoFacade.getTotalAmountOfUnLockingTxOutputs());
        Coin totalUnlockedAmount = Coin.valueOf(daoFacade.getTotalAmountOfUnLockedTxOutputs());
        Coin totalConfiscatedAmount = Coin.valueOf(daoFacade.getTotalAmountOfConfiscatedTxOutputs());
        Coin totalAmountOfInvalidatedBsq = Coin.valueOf(daoFacade.getTotalAmountOfInvalidatedBsq());

        totalBurntFeeAmountTextField.setText("-" + bsqFormatter.formatAmountWithGroupSeparatorAndCode(totalBurntFee));
        totalLockedUpAmountTextField.setText(bsqFormatter.formatAmountWithGroupSeparatorAndCode(totalLockedUpAmount));
        totalUnlockingAmountTextField.setText(bsqFormatter.formatAmountWithGroupSeparatorAndCode(totalUnlockingAmount));
        totalUnlockedAmountTextField.setText(bsqFormatter.formatAmountWithGroupSeparatorAndCode(totalUnlockedAmount));
        totalConfiscatedAmountTextField.setText(bsqFormatter.formatAmountWithGroupSeparatorAndCode(totalConfiscatedAmount));
        String minusSign = totalAmountOfInvalidatedBsq.isPositive() ? "-" : "";
        totalAmountOfInvalidatedBsqTextField.setText(minusSign + bsqFormatter.formatAmountWithGroupSeparatorAndCode(totalAmountOfInvalidatedBsq));

        updateChartSeries();
    }

    private void updateChartSeries() {
        var updatedBurntBsq = updateBSQBurnt();
        updateBSQBurntMA(updatedBurntBsq);
        updateBSQIssued();
    }

    private List<XYChart.Data<Number, Number>> updateBSQBurnt() {
        seriesBSQBurnt.getData().clear();

        Set<Tx> burntTxs = new HashSet<>(daoStateService.getBurntFeeTxs());
        burntTxs.addAll(daoStateService.getInvalidTxs());

        Map<LocalDate, List<Tx>> burntBsqByDay = burntTxs.stream()
                .sorted(Comparator.comparing(Tx::getTime))
                .collect(Collectors.groupingBy(item -> new Date(item.getTime()).toLocalDate()
                        .with(ADJUSTERS.get(DAY))));

        List<XYChart.Data<Number, Number>> updatedBurntBsq = burntBsqByDay.keySet().stream()
                .map(date -> {
                    ZonedDateTime zonedDateTime = date.atStartOfDay(ZoneId.systemDefault());
                    return new XYChart.Data<Number, Number>(
                            zonedDateTime.toInstant().getEpochSecond(),
                            burntBsqByDay.get(date)
                                    .stream()
                                    .mapToDouble(Tx::getBurntBsq)
                                    .sum()
                    );
                })
                .collect(Collectors.toList());

        seriesBSQBurnt.getData().setAll(updatedBurntBsq);

        return updatedBurntBsq;
    }

    private void updateBSQBurntMA(List<XYChart.Data<Number, Number>> updatedBurntBsq) {
        seriesBSQBurntMA.getData().clear();

        Comparator<Number> compareXChronology =
                Comparator.comparingInt(Number::intValue);

        Comparator<XYChart.Data<Number, Number>> compareXyDataChronology =
                (xyData1, xyData2) ->
                        compareXChronology.compare(
                                xyData1.getXValue(),
                                xyData2.getXValue());

        var sortedUpdatedBurntBsq = updatedBurntBsq
                .stream()
                .sorted(compareXyDataChronology)
                .collect(Collectors.toList());

        var burntBsqXValues = sortedUpdatedBurntBsq.stream().map(XYChart.Data::getXValue);
        var burntBsqYValues = sortedUpdatedBurntBsq.stream().map(XYChart.Data::getYValue);

        var maPeriod = 15;
        var burntBsqMAYValues =
                MovingAverageUtils.simpleMovingAverage(
                        burntBsqYValues,
                        maPeriod);

        BiFunction<Number, Double, XYChart.Data<Number, Number>> xyToXyData =
                XYChart.Data::new;

        List<XYChart.Data<Number, Number>> burntBsqMA =
                zip(burntBsqXValues, burntBsqMAYValues, xyToXyData)
                        .filter(xyData -> Double.isFinite(xyData.getYValue().doubleValue()))
                        .collect(Collectors.toList());

        seriesBSQBurntMA.getData().setAll(burntBsqMA);
    }

    private void updateBSQIssued() {
        seriesBSQIssued.getData().clear();

        Stream<Issuance> bsqByCompensation = daoStateService.getIssuanceSet(IssuanceType.COMPENSATION).stream()
                .sorted(Comparator.comparing(Issuance::getChainHeight));

        Stream<Issuance> bsqByReimbursement = daoStateService.getIssuanceSet(IssuanceType.REIMBURSEMENT).stream()
                .sorted(Comparator.comparing(Issuance::getChainHeight));

        Map<LocalDate, List<Issuance>> bsqAddedByVote = Stream.concat(bsqByCompensation, bsqByReimbursement)
                .collect(Collectors.groupingBy(item -> new Date(daoFacade.getBlockTime(item.getChainHeight())).toLocalDate()
                        .with(ADJUSTERS.get(MONTH))));

        List<XYChart.Data<Number, Number>> updatedAddedBSQ = bsqAddedByVote.keySet().stream()
                .map(date -> {
                    ZonedDateTime zonedDateTime = date.atStartOfDay(ZoneId.systemDefault());
                    return new XYChart.Data<Number, Number>(zonedDateTime.toInstant().getEpochSecond(), bsqAddedByVote.get(date)
                            .stream()
                            .mapToDouble(Issuance::getAmount)
                            .sum());
                })
                .collect(Collectors.toList());

        seriesBSQIssued.getData().setAll(updatedAddedBSQ);
    }

    private void activateButtons() {
        zoomToInliersSlide.setSelected(isZoomingToInliers);
        zoomToInliersSlide.setOnAction(e -> handleZoomToInliersSlide(!isZoomingToInliers));
    }

    private void deactivateButtons() {
        zoomToInliersSlide.setOnAction(null);
    }

    private void handleZoomToInliersSlide(boolean shouldActivate) {
        isZoomingToInliers = !isZoomingToInliers;
        if (shouldActivate) {
            activateZoomingToInliers();
        } else {
            deactivateZoomingToInliers();
        }
    }

    private void activateZoomingToInliers() {
        seriesBSQBurnt.getData().addListener(changeListenerBSQBurnt);

        // Initial zoom has to be triggered manually; otherwise, it
        // would be triggered only on a change event in the series
        triggerZoomToInliers();
    }

    private void deactivateZoomingToInliers() {
        seriesBSQBurnt.getData().removeListener(changeListenerBSQBurnt);

        // Reactivate automatic ranging
        yAxisBSQBurnt.autoRangingProperty().set(true);
    }

    private void triggerZoomToInliers() {
        var xyValues = seriesBSQBurnt.getData();
        AxisInlierUtils.zoomToInliers(
                yAxisBSQBurnt,
                xyValues,
                chartMaxNumberOfTicks,
                chartPercentToTrim,
                chartHowManyStdDevsConstituteOutlier
        );
    }

    // When Guava version is bumped to at least 21.0,
    // can be replaced with com.google.common.collect.Streams.zip
    public static <L, R, T> Stream<T> zip(
            Stream<L> leftStream,
            Stream<R> rightStream,
            BiFunction<L, R, T> combiner
    ) {
        var lefts = leftStream.spliterator();
        var rights = rightStream.spliterator();
        var spliterator =
                new AbstractSpliterator<T>(
                        Long.min(
                                lefts.estimateSize(),
                                rights.estimateSize()
                        ),
                        lefts.characteristics() & rights.characteristics()
                ) {
                    @Override
                    public boolean tryAdvance(Consumer<? super T> action) {
                        return lefts.tryAdvance(
                                left -> rights.tryAdvance(
                                        right -> action.accept(combiner.apply(left, right))
                                )
                        );
                    }
                };
        return StreamSupport.stream(spliterator, false);
    }
}
