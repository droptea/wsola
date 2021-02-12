package priv.droptea.emotion.panel;

import java.awt.Color;
import java.awt.Font;
import java.util.concurrent.LinkedBlockingQueue;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.StandardChartTheme;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.title.LegendTitle;
import org.jfree.chart.title.TextTitle;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import priv.droptea.emotion.AudioEvent;
import priv.droptea.emotion.AudioEvent.DataForAnalysisInWaveformChart;

public class WaveformChartPanel extends ChartPanel {
	private static final long serialVersionUID = 1L;
	/**
	 * 通过这个字段来指定将要显示的波形图
	 */
	private String what;
	/**
	 * 显示分析帧的音频波形图
	 */
	public static final String what_analysisFrameWaveformChart = "what_analysisFrameWaveformChart";

	/**
	 * 显示未经处理的音频波形图
	 */
	public static final String what_inputWaveformChart = "what_inputWaveformChart";
	/**
	 * 显示经过wsola处理的音频波形图
	 */
	public static final String what_outputWaveformChartWsola = "what_outputWaveformChartWsola";
	/**
	 * 显示经过rateTransposer处理的音频波形图
	 */
	public static final String what_outputWaveformChartRt = "what_outputWaveformChartRt";

	XYSeriesCollection mXYSeriesCollection;

	private LinkedBlockingQueue<AudioEvent> mLinkedBlockingQueue;
	/**
	 * 波形图总共显示多少个音频块的数据
	 */
	private int showSoundBlockSizeSum = 2;
	/**
	 * 波形图已经显示了多少个音频块的数据
	 */
	private int curShowSoundBlockSize = 0;
	/**
	 * 记录显示的数据的X轴下标，每次显示一个数据就自增1，用来作为下一条数据的X轴下标
	 */
	private int indexX = 0;
	/**
	 * 是否忽略第一块音频数据
	 */
	private boolean isIgnoreFirstBlock = true;

	private WaveformChartPanel(String what,String title) {
		super(null);
		// TODO Auto-generated constructor stub
		// 创建主题样式
		StandardChartTheme standardChartTheme = new StandardChartTheme("CN");
		// 设置标题字体
		standardChartTheme.setExtraLargeFont(new Font("隶书", Font.BOLD, 20));
		// 设置图例的字体
		standardChartTheme.setRegularFont(new Font("隶书", Font.PLAIN, 12));
		// 设置轴向的字体
		standardChartTheme.setLargeFont(new Font("隶书", Font.PLAIN, 15));
		// 应用主题样式
		ChartFactory.setChartTheme(standardChartTheme);
		
		this.what = what;
		mXYSeriesCollection = new XYSeriesCollection();
		JFreeChart mChart = ChartFactory.createXYLineChart(title, "按时间顺序采样的数据", "归一化的振幅",
				mXYSeriesCollection, PlotOrientation.VERTICAL, true, true, false);
		mLinkedBlockingQueue = new LinkedBlockingQueue<AudioEvent>();

		this.setChart(mChart);
		/*
		 * LegendTitle legend = mChart.getLegend(); if (legend!=null) {
		 * legend.setItemFont(new Font("隶书", Font.PLAIN, 15)); } TextTitle textTitle =
		 * mChart.getTitle(); textTitle.setFont(new Font("隶书",Font.BOLD,20));
		 */
		
		startQueueHandler();
	}

	private void startQueueHandler() {
		new Thread(new Runnable() {

			@Override
			public void run() {
				while (true) {

					try {
						AudioEvent audioEvent = mLinkedBlockingQueue.take();
						/**
						 * 不分析第一块音频数据
						 */
						if (isIgnoreFirstBlock) {
							isIgnoreFirstBlock = false;
							continue;
						}
						if (curShowSoundBlockSize >= showSoundBlockSizeSum)
							continue;
						curShowSoundBlockSize++;
						DataForAnalysisInWaveformChart mDataForAnalysisInWaveformChart = audioEvent
								.getDataForAnalysisInWaveformChart();
						if (what_analysisFrameWaveformChart.equals(what)) {
							float[] audioFloatBuffer = mDataForAnalysisInWaveformChart.getFloatBufferOriginal();
							int duplicateLengthInAnalysisFrame = mDataForAnalysisInWaveformChart.getDuplicateLengthInAnalysisFrame();
							//重复数据
							XYSeries mXYSeries_duplicate = new XYSeries("重复数据" + curShowSoundBlockSize);
							for (int i = 0; i < duplicateLengthInAnalysisFrame; i++) {
								mXYSeries_duplicate.add(indexX, audioFloatBuffer[i]);
								indexX++;
							}
							mXYSeriesCollection.addSeries(mXYSeries_duplicate);
							((XYPlot) (getChart().getPlot())).getRenderer().setSeriesPaint(
									mXYSeriesCollection.getSeriesCount() - 1, new Color(0xFF,0xE4,0xE1));
							//有效帧
							XYSeries mXYSeries_effectiveFrame = new XYSeries("有效帧" + curShowSoundBlockSize);
							for (int i = duplicateLengthInAnalysisFrame; i <  audioFloatBuffer.length; i++) {
								mXYSeries_effectiveFrame.add(indexX, audioFloatBuffer[i]);
								indexX++;
							}
							mXYSeriesCollection.addSeries(mXYSeries_effectiveFrame);
							((XYPlot) (getChart().getPlot())).getRenderer().setSeriesPaint(
									mXYSeriesCollection.getSeriesCount() - 1, new Color(0xBF,0x3E,0xFF));
						
						}if (what_inputWaveformChart.equals(what)) {
							float[] audioFloatBuffer = mDataForAnalysisInWaveformChart.getFloatBufferOriginal();
							int seekWinOffsetWsola = mDataForAnalysisInWaveformChart.getSeekWinOffsetWsola();
							int overlapWsola = mDataForAnalysisInWaveformChart.getOverlapWsola();
							int dataNotOverlapWsola = mDataForAnalysisInWaveformChart.getDataNotOverlapWsola();
							
							//搜索窗移动距离
							XYSeries mXYseek_seekWindowMovOffset = new XYSeries("搜索窗实际移动的距离" + curShowSoundBlockSize);
							for (int i = 0; i < seekWinOffsetWsola; i++) {
								mXYseek_seekWindowMovOffset.add(indexX, audioFloatBuffer[i]);
								indexX++;
							}
							mXYSeriesCollection.addSeries(mXYseek_seekWindowMovOffset);
							((XYPlot) (getChart().getPlot())).getRenderer().setSeriesPaint(
									mXYSeriesCollection.getSeriesCount() - 1, new Color(0x98, 0xfb, 0x98));
							//重叠区域
							XYSeries mXYOverlapWsola1 = new XYSeries("头部重叠区域" + curShowSoundBlockSize);
							for (int i = seekWinOffsetWsola; i < seekWinOffsetWsola+overlapWsola; i++) {
								mXYOverlapWsola1.add(indexX, audioFloatBuffer[i]);
								indexX++;
							}
							mXYSeriesCollection.addSeries(mXYOverlapWsola1);
							((XYPlot) (getChart().getPlot())).getRenderer().setSeriesPaint(
									mXYSeriesCollection.getSeriesCount() - 1, new Color(0xf6, 0xde, 0x13));
							
							//合成帧
							XYSeries mXYDataNotOverlapWsola = new XYSeries("非重叠区域" + curShowSoundBlockSize);
							for (int i = overlapWsola + seekWinOffsetWsola; i <  overlapWsola + seekWinOffsetWsola+dataNotOverlapWsola; i++) {
								mXYDataNotOverlapWsola.add(indexX, audioFloatBuffer[i]);
								indexX++;
							}
							mXYSeriesCollection.addSeries(mXYDataNotOverlapWsola);
							//重叠区域
							XYSeries mXYOverlapWsola = new XYSeries("尾部重叠区域" + curShowSoundBlockSize);
							for (int i = overlapWsola + seekWinOffsetWsola+dataNotOverlapWsola; i < overlapWsola + seekWinOffsetWsola+dataNotOverlapWsola+overlapWsola; i++) {
								mXYOverlapWsola.add(indexX, audioFloatBuffer[i]);
								indexX++;
							}
							mXYSeriesCollection.addSeries(mXYOverlapWsola);
							((XYPlot) (getChart().getPlot())).getRenderer().setSeriesPaint(
									mXYSeriesCollection.getSeriesCount() - 1, new Color(0xf6, 0xde, 0x13));
							
							//none
							XYSeries mXYNone = new XYSeries("丢弃的数据" + curShowSoundBlockSize);
							for (int i = overlapWsola + seekWinOffsetWsola+dataNotOverlapWsola+overlapWsola; i < audioFloatBuffer.length; i++) {
								mXYNone.add(indexX, audioFloatBuffer[i]);
								indexX++;
							}
							mXYSeriesCollection.addSeries(mXYNone);
							((XYPlot) (getChart().getPlot())).getRenderer().setSeriesPaint(
									mXYSeriesCollection.getSeriesCount() - 1, new Color(0xff, 0xff, 0xff));
							//valideData表示音频实际的数据长度，allData中包括了重叠区域
							System.out.println(what+"_valideData:" + (indexX-mDataForAnalysisInWaveformChart.getDuplicateLengthInAnalysisFrame()));
						} else if (what_outputWaveformChartWsola.equals(what)) {
							float[] audioFloatBuffer = mDataForAnalysisInWaveformChart.getFloatBufferWsola();
							int overlapWsola = mDataForAnalysisInWaveformChart.getOverlapWsola();
							//overlapWsola
							XYSeries mXYOverlapWsola = new XYSeries("重叠区域" + curShowSoundBlockSize);
							for (int i = 0; i < overlapWsola; i++) {
								mXYOverlapWsola.add(indexX, audioFloatBuffer[i]);
								indexX++;
							}
							mXYSeriesCollection.addSeries(mXYOverlapWsola);
							((XYPlot) (getChart().getPlot())).getRenderer().setSeriesPaint(
									mXYSeriesCollection.getSeriesCount() - 1, new Color(0xf6, 0xde, 0x13));
							//dataNotOverlapWsola
							XYSeries mXYDataNotOverlapWsola = new XYSeries("非重叠区域" + curShowSoundBlockSize);
							for (int i = overlapWsola; i < audioFloatBuffer.length; i++) {
								mXYDataNotOverlapWsola.add(indexX, audioFloatBuffer[i]);
								indexX++;
							}
							mXYSeriesCollection.addSeries(mXYDataNotOverlapWsola);
						} else if(what_outputWaveformChartRt.equals(what)) {
							float[] audioFloatBuffer = mDataForAnalysisInWaveformChart.getFloatBufferCur();
							XYSeries mXYDataNotOverlapWsola = new XYSeries("soundData" + curShowSoundBlockSize);
							for (int i = 0; i < audioFloatBuffer.length; i++) {
								mXYDataNotOverlapWsola.add(indexX, audioFloatBuffer[i]);
								indexX++;
							}
							mXYSeriesCollection.addSeries(mXYDataNotOverlapWsola);
						}
						System.out.println(what+"_allDataLength:" + indexX);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			}
		}).start();
	}

	public static WaveformChartPanel getInstant(String what,String title) {
		return new WaveformChartPanel(what,title);
	}

	public void addAudioEvent(AudioEvent audioEvent) {
		mLinkedBlockingQueue.offer(audioEvent);

	}
}
