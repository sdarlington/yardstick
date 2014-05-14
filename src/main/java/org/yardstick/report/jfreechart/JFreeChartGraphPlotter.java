/*
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package org.yardstick.report.jfreechart;

import com.beust.jcommander.*;
import org.jfree.chart.*;
import org.jfree.chart.axis.*;
import org.jfree.chart.entity.*;
import org.jfree.chart.plot.*;
import org.jfree.chart.renderer.xy.*;
import org.jfree.chart.title.*;
import org.jfree.data.xy.*;
import org.jfree.ui.*;
import org.yardstick.*;
import org.yardstick.writers.*;

import java.awt.*;
import java.io.*;
import java.text.*;
import java.util.*;
import java.util.List;

import static java.awt.Color.*;
import static org.yardstick.report.jfreechart.JFreeChartGenerationMode.*;
import static org.yardstick.writers.BenchmarkProbePointCsvWriter.*;

/**
 * JFreeChart graph plotter.
 */
public class JFreeChartGraphPlotter {
    /** */
    private static final String INPUT_FILE_EXTENSION = ".csv";

    /** */
    private static final Color[] PLOT_COLORS = {GREEN, BLUE, RED, ORANGE, CYAN, MAGENTA,
        new Color(255, 0, 137), new Color(163, 143, 255), new Color(76, 255, 153)};

    /**
     * @param cmdArgs Arguments.
     * @throws Exception If failed.
     */
    public static void main(String[] cmdArgs) throws Exception {
        JFreeChartGraphPlotterArguments args = new JFreeChartGraphPlotterArguments();

        JCommander jCommander = BenchmarkUtils.jcommander(cmdArgs, args, "<graph-plotter>");

        if (args.help()) {
            jCommander.usage();

            return;
        }

        if (args.inputFolders() == null) {
            System.out.println("Input folders are not defined.");

            return;
        }

        String[] inFoldersAsString = args.inputFolders().split(",");

        File[] inFolders = new File[inFoldersAsString.length];

        for (int i = 0; i < inFoldersAsString.length; i++)
            inFolders[i] = new File(inFoldersAsString[i]).getAbsoluteFile();

        for (File inFolder : inFolders) {
            if (!inFolder.exists()) {
                System.out.println("Folder '" + inFolder.getAbsolutePath() + "' does not exist.");

                return;
            }
        }

        JFreeChartGenerationMode mode = args.generationMode();

        if (mode == COMPOUND)
            processCompoundMode(inFolders, args);
        else if (mode == COMPARISON)
            processComparisonMode(inFolders, args);
        else if (mode == STANDARD || mode == null)
            processStandardMode(inFolders, args);
        else
            throw new IllegalStateException("Unknown generation mode: " + args.generationMode() + ".");
    }

    /**
     * @param inFolders Input folders.
     * @param args Arguments.
     * @throws Exception If failed.
     */
    private static void processCompoundMode(File[] inFolders, JFreeChartGraphPlotterArguments args) throws Exception {
        Map<String, List<File>> res = new HashMap<>();

        for (File inFolder : inFolders) {
            Map<String, List<File>> map = files(inFolder);

            mergeMaps(res, map);
        }

        Set<String> folders = new HashSet<>();

        for (List<File> files : res.values()) {
            for (File file : files) {
                File par = file.getParentFile();

                if (par != null)
                    folders.add(par.getName());
            }
        }

        StringBuilder outFolSuf = new StringBuilder();

        for (String f : folders) {
            String s = parseTime(f);

            if (!s.isEmpty())
                outFolSuf.append(s).append('_');
        }

        if (outFolSuf.length() > 0)
            outFolSuf.delete(outFolSuf.length() - 1, outFolSuf.length());

        String parent = inFolders[0].getParent() == null ? inFolders[0].getName() : inFolders[0].getParent();

        String parentFolderName = COMPOUND.name().toLowerCase() + "_results_" + outFolSuf.toString();

        if (parentFolderName.length() > 255)
            parentFolderName = parentFolderName.substring(0, 255);

        File folderToWrite = new File(parent, parentFolderName);

        if (!folderToWrite.exists()) {
            if (!folderToWrite.mkdir()) {
                System.out.println("Can not create folder '" + folderToWrite.getAbsolutePath() + "'.");

                return;
            }
        }

        processFilesPerProbe(res, folderToWrite, args);
    }

    /**
     * @param inFolders Input folders.
     * @param args Arguments.
     * @throws Exception If failed.
     */
    private static void processComparisonMode(File[] inFolders, JFreeChartGraphPlotterArguments args) throws Exception {
        Collection<File[]> foldersToCompare = new ArrayList<>();

        StringBuilder outParentFolSuf = new StringBuilder();

        for (File inFolder : inFolders) {
            File[] dirs = inFolder.listFiles();

            if (dirs == null || dirs.length == 0)
                continue;

            foldersToCompare.add(dirs);

            String fName = inFolder.getName();

            String s = fName.startsWith("results_") ? fName.replace("results_", "") : "";

            if (!s.isEmpty())
                outParentFolSuf.append(s).append('_');
        }

        if (outParentFolSuf.length() > 0)
            outParentFolSuf.delete(outParentFolSuf.length() - 1, outParentFolSuf.length());

        String parent = inFolders[0].getParent() == null ? inFolders[0].getName() : inFolders[0].getParent();

        String parentFolderName = COMPARISON.name().toLowerCase() + "_results_" + outParentFolSuf.toString();

        if (parentFolderName.length() > 255)
            parentFolderName = parentFolderName.substring(0, 255);

        File parentFolderToWrite = new File(parent, parentFolderName);

        if (!parentFolderToWrite.exists()) {
            if (!parentFolderToWrite.mkdir()) {
                System.out.println("Can not create folder '" + parentFolderToWrite.getAbsolutePath() + "'.");

                return;
            }
        }

        int idx = -1;

        while (true) {
            idx++;

            boolean filesExist = false;

            Map<String, List<File>> res = new HashMap<>();

            StringBuilder outFolSuf = new StringBuilder();

            for (File[] files : foldersToCompare) {
                if (files.length <= idx)
                    continue;

                filesExist = true;

                File f = files[idx];

                if (f.isDirectory()) {
                    Map<String, List<File>> map = files(f);

                    mergeMaps(res, map);

                    String s = parseTime(f.getName());

                    if (!s.isEmpty())
                        outFolSuf.append(s).append('_');
                }
            }

            if (!filesExist)
                break;

            if (outFolSuf.length() > 0)
                outFolSuf.delete(outFolSuf.length() - 1, outFolSuf.length());

            String idxPrefix = idx < 9 ? "00" : idx < 99 ? "0" : "";

            String folName = idxPrefix + (idx + 1) + '_' + outFolSuf.toString();

            if (folName.length() > 255)
                folName = folName.substring(0, 255);

            File folderToWrite = new File(parentFolderToWrite, folName);

            if (!folderToWrite.exists()) {
                if (!folderToWrite.mkdir()) {
                    System.out.println("Can not create folder '" + folderToWrite.getAbsolutePath() + "'.");

                    continue;
                }
            }

            processFilesPerProbe(res, folderToWrite, args);
        }
    }

    /**
     * @param inFolders Input folders.
     * @param args Arguments.
     * @throws Exception If failed.
     */
    private static void processStandardMode(File[] inFolders, JFreeChartGraphPlotterArguments args) throws Exception {
        for (File inFolder : inFolders) {
            Map<String, List<JFreeChartPlotInfo>> infoMap = new HashMap<>();

            for (List<File> files : files(inFolder).values()) {
                for (File file : files) {
                    System.out.println("Processing file '" + file + "'.");

                    try {
                        List<PlotData> plotData = readData(file);

                        processPlots(file.getParentFile(), Collections.singleton(plotData), infoMap);
                    }
                    catch (Exception e) {
                        System.out.println("Exception is raised during file '" + file + "' processing.");

                        e.printStackTrace();
                    }
                }
            }

            JFreeChartResultPageGenerator.generate(inFolder, args, infoMap);
        }
    }

    /**
     * @param res Resulted map.
     * @param folderToWrite Folder to write results to.
     * @param args Arguments.
     * @throws Exception If failed.
     */
    private static void processFilesPerProbe(Map<String, List<File>> res, File folderToWrite,
        JFreeChartGraphPlotterArguments args) throws Exception {
        Map<String, List<JFreeChartPlotInfo>> infoMap = new HashMap<>();

        for (Map.Entry<String, List<File>> entry : res.entrySet()) {
            Collection<List<PlotData>> plots = new ArrayList<>(entry.getValue().size());

            for (File file : entry.getValue()) {
                System.out.println("Processing file '" + file + "'.");

                try {
                    plots.add(readData(file));
                }
                catch (Exception e) {
                    System.out.println("Exception is raised during file '" + file + "' processing.");

                    e.printStackTrace();
                }
            }

            processPlots(folderToWrite, plots, infoMap);
        }

        if (!infoMap.isEmpty())
            JFreeChartResultPageGenerator.generate(folderToWrite, args, infoMap);
    }

    /**
     * @param fName Folder name.
     * @return Substring containing benchmark time.
     */
    private static String parseTime(String fName) {
        int i = fName.indexOf('_', fName.indexOf('_') + 1);

        if (i != -1) {
            try {
                String time = fName.substring(0, i);

                BenchmarkProbePointCsvWriter.FORMAT.parse(time);

                return time;
            }
            catch (ParseException ignored) {
                return "";
            }
        }

        return "";
    }

    /**
     * @param res Resulted map.
     * @param map Map to merge.
     */
    private static void mergeMaps(Map<String, List<File>> res, Map<String, List<File>> map) {
        for (Map.Entry<String, List<File>> entry : map.entrySet()) {
            List<File> list = res.get(entry.getKey());

            if (list == null) {
                list = new ArrayList<>();

                res.put(entry.getKey(), list);
            }

            list.addAll(entry.getValue());
        }
    }

    /**
     * @param folder Folder to scan for files.
     * @return Collection of files.
     */
    private static Map<String, List<File>> files(File folder) {
        File[] dirs = folder.listFiles();

        if (dirs == null || dirs.length == 0)
            return Collections.emptyMap();

        Map<String, List<File>> res = new HashMap<>();

        for (File dir : dirs) {
            if (dir.isDirectory()) {
                File[] files = dir.listFiles();

                if (files == null || files.length == 0)
                    continue;

                for (File file : files)
                    addFile(file, res);
            }
            else
                addFile(dir, res);
        }

        return res;
    }

    /**
     * @param file File to add.
     * @param res Resulted collection.
     */
    private static void addFile(File file, Map<String, List<File>> res) {
        if (file.isDirectory())
            return;

        if (!file.canRead()) {
            System.out.println("File '" + file + "' can not be read.");

            return;
        }

        if (file.getName().endsWith(INPUT_FILE_EXTENSION)) {
            List<File> list = res.get(file.getName());

            if (list == null) {
                list = new ArrayList<>();

                res.put(file.getName(), list);
            }

            list.add(file);
        }
    }

    /**
     * @param folderToWrite Folder to write the resulted charts.
     * @param plots Collections of plots.
     * @param infoMap Map with additional plot info.
     * @throws Exception If failed.
     */
    private static void processPlots(File folderToWrite, Collection<List<PlotData>> plots,
        Map<String, List<JFreeChartPlotInfo>> infoMap) throws Exception {
        ChartRenderingInfo info = new ChartRenderingInfo(new StandardEntityCollection());

        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer(true, false);

        int idx = -1;

        while (true) {
            idx++;

            DefaultXYDataset dataSet = new DefaultXYDataset();

            List<JFreeChartPlotInfo> infoList = new ArrayList<>();

            String xAxisLabel = "";
            String yAxisLabel = "";
            String plotName = "";

            for (List<PlotData> plotData0 : plots) {
                if (plotData0.size() <= idx)
                    continue;

                PlotData plotData = plotData0.get(idx);

                dataSet.addSeries(plotData.series().seriesName, plotData.series().data);

                xAxisLabel = plotData.xAxisLabel;
                yAxisLabel = plotData.yAxisLabel;
                plotName = plotData.plotName();

                infoList.add(info(plotData.series()));
            }

            if (infoList.isEmpty())
                break;

            JFreeChart chart = ChartFactory.createXYLineChart(
                "",
                xAxisLabel,
                yAxisLabel,
                dataSet,
                PlotOrientation.VERTICAL,
                false,
                false,
                false);

            AxisSpace as = new AxisSpace();

            as.add(150, RectangleEdge.LEFT);

            XYPlot plot = (XYPlot)chart.getPlot();

            BasicStroke stroke = new BasicStroke(1);

            plot.setRenderer(renderer);
            plot.setBackgroundPaint(WHITE);
            plot.setRangeGridlinePaint(GRAY);
            plot.setDomainGridlinePaint(GRAY);
            plot.setFixedRangeAxisSpace(as);
            plot.setOutlineStroke(stroke);

            for (int i = 0; i < infoList.size(); i++) {
                Color color = PLOT_COLORS[i % PLOT_COLORS.length];

                renderer.setSeriesPaint(i, color);
                renderer.setSeriesStroke(i, new BasicStroke(3)); // Line thickness.

                infoList.get(i).color(Integer.toHexString(color.getRGB()).substring(2));
            }

            ValueAxis axis = plot.getRangeAxis();

            Font font = new Font(axis.getTickLabelFont().getName(), Font.BOLD, axis.getTickLabelFont().getSize() + 3);

            axis.setTickLabelFont(font);
            axis.setLabelFont(font);
            plot.getDomainAxis().setTickLabelFont(font);
            plot.getDomainAxis().setLabelFont(font);

            chart.setTitle(new TextTitle(yAxisLabel, new Font(font.getName(), font.getStyle(), 30)));

            File res = new File(folderToWrite, plotName + ".png");

            ChartUtilities.saveChartAsPNG(res, chart, 800, 400, info);

            infoMap.put(res.getAbsolutePath(), infoList);

            System.out.println("Resulted chart is saved to file '" + res.getAbsolutePath() + "'.");
        }

        System.out.println();
    }

    /**
     * @param series Plot series.
     * @return Graph info.
     */
    private static JFreeChartPlotInfo info(PlotSeries series) {
        double sum = 0;
        double min = Long.MAX_VALUE;
        double max = Long.MIN_VALUE;

        int len = series.data[1].length;

        if (len == 1) {
            double val = series.data[1][0];

            return new JFreeChartPlotInfo(series.seriesName, val, val, val, 0);
        }

        for (int i = 0; i < len; i++) {
            double val = series.data[1][i];

            min = Math.min(min, val);

            max = Math.max(max, val);

            sum += val;
        }

        double avg = sum / len;

        double s = 0;

        for (int i = 0; i < len; i++) {
            double val = series.data[1][i];

            s += Math.pow((val - avg), 2);
        }

        double stdDiv = Math.sqrt(s / (len - 1));

        return new JFreeChartPlotInfo(series.seriesName, avg, min, max, stdDiv);
    }

    /**
     * @param file File.
     * @return Collection of plot data.
     * @throws Exception If failed.
     */
    private static List<PlotData> readData(File file) throws Exception {
        List<PlotData> data = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file)))) {
            long initTime = 0;

            String[] metaInfo = null;

            for (String line; (line = br.readLine()) != null;) {
                if (line.startsWith("--"))
                    continue;

                if (line.startsWith(META_INFO_PREFIX)) {
                    metaInfo = line.substring(META_INFO_PREFIX.length()).split("\"" + META_INFO_SEPARATOR + "\"");

                    continue;
                }

                String[] split = line.split(",");

                if (data.isEmpty()) {
                    initTime = Long.parseLong(split[0]);

                    int plotNum = split.length - 1;

                    if (plotNum < 1)
                        throw new Exception("Invalid data file.");

                    String xAxisLabel = metaInfo == null || metaInfo.length == 0 ? "" : metaInfo[0].replace("\"", "");

                    for (int i = 0; i < plotNum; i++) {
                        PlotSeries single = new PlotSeries(file.getParentFile().getName());

                        String yAxisLabel = metaInfo == null || i + 1 >= metaInfo.length ? "" :
                            metaInfo[i + 1].replace("\"", "");

                        String plotName = file.getName().replace(INPUT_FILE_EXTENSION, "");

                        String cnt = Integer.toString(i + 1);

                        cnt = cnt.length() == 1 ? "0" + cnt : cnt;

                        data.add(new PlotData("Plot_" + plotName + "_" + cnt, single, xAxisLabel, yAxisLabel));
                    }
                }

                double[] tup = new double[split.length];

                for (int i = 0; i < tup.length; i++) {
                    double d = i == 0 ? (Long.parseLong(split[0]) - initTime) : Double.parseDouble(split[i]);

                    tup[i] = d;
                }

                for (int i = 0; i < split.length - 1; i++)
                    data.get(i).series().rawData.add(new double[] {tup[0], tup[i + 1]});
            }

            for (PlotData plotData : data)
                plotData.series().finish();

            return data;
        }
    }

    /**
     *
     */
    private static class PlotData {
        /** */
        private final PlotSeries series;

        /** */
        private final String plotName;

        /** */
        private final String xAxisLabel;

        /** */
        private final String yAxisLabel;

        /**
         * @param plotName Plot name.
         * @param series Series.
         * @param xAxisLabel X axis label.
         * @param yAxisLabel Y axis label.
         */
        PlotData(String plotName, PlotSeries series, String xAxisLabel, String yAxisLabel) {
            this.plotName = plotName;
            this.series = series;
            this.xAxisLabel = xAxisLabel;
            this.yAxisLabel = yAxisLabel;
        }

        /**
         * @return Series.
         */
        public PlotSeries series() {
            return series;
        }

        /**
         * @return Plot name.
         */
        public String plotName() {
            return plotName;
        }
    }

    /**
     *
     */
    private static class PlotSeries {
        /** */
        private final String seriesName;

        /** */
        private List<double[]> rawData = new ArrayList<>();

        /** */
        private double[][] data;

        /**
         * @param seriesName Series name.
         */
        PlotSeries(String seriesName) {
            this.seriesName = seriesName;
        }

        /**
         *
         */
        public void finish() {
            data = new double[2][];

            data[0] = new double[rawData.size()];
            data[1] = new double[rawData.size()];

            for (int i = 0; i < rawData.size(); i++) {
                double[] tup = rawData.get(i);

                data[0][i] = tup[0];
                data[1][i] = tup[1];
            }

            // No need raw data anymore.
            rawData = null;
        }
    }
}
