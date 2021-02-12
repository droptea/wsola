package priv.droptea.emotion.util;

import java.awt.EventQueue;
import java.awt.Font;

import javax.swing.JFrame;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.StandardChartTheme;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import priv.droptea.emotion.resample.FilterKit;

public class chartTool {

	private JFrame frame;

	/**
	 * Launch the application.
	 */
	public static void main(String[] args) {
		EventQueue.invokeLater(new Runnable() {
			public void run() {
				try {
					int Npc = 4096;
					int Beta = 6;
					int Nmult = 11;
					double Rolloff = 0.90;
					int Nwing = Npc * (Nmult - 1) / 2;
					double[] Imp64 = new double[Nwing];
					FilterKit.lrsLpFilter(Imp64, Nwing, 0.5 * Rolloff, Beta, Npc);
					chartTool window = new chartTool(Imp64);
					window.frame.setVisible(true);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		});
	}
	public static void ChartTool(){
		EventQueue.invokeLater(new Runnable() {
			public void run() {
				try {
					chartTool window = new chartTool();
					window.frame.setVisible(true);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		});
		
	}

	/**
	 * Create the application.
	 */
	public chartTool(double[] imp64) {
		initialize(imp64);
	}
	/**
	 * Create the application.
	 */
	public chartTool() {
		initialize(null);
	}

	/**
	 * Initialize the contents of the frame.
	 */
	private void initialize(double[] array) {
		frame = new JFrame();
		frame.setBounds(100, 100, 450, 300);
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		if(array!=null) {
			StandardChartTheme standardChartTheme = new StandardChartTheme("CN");
			// 设置标题字体
			standardChartTheme.setExtraLargeFont(new Font("隶书", Font.BOLD, 20));
			// 设置图例的字体
			standardChartTheme.setRegularFont(new Font("隶书", Font.PLAIN, 12));
			// 设置轴向的字体
			standardChartTheme.setLargeFont(new Font("隶书", Font.PLAIN, 15));
			// 应用主题样式
			ChartFactory.setChartTheme(standardChartTheme);
			XYSeriesCollection mXYSeriesCollection = new XYSeriesCollection();
			JFreeChart mChart = ChartFactory.createXYLineChart("折线图", "X", "Y",
					mXYSeriesCollection, PlotOrientation.VERTICAL, true, true, false);
			XYSeries mXYSeries_duplicate = new XYSeries("数据");
			int indexX = 0;
			for (int i = 0; i < array.length; i++) {
				mXYSeries_duplicate.add(indexX, array[i]);
				indexX++;
			}
			mXYSeriesCollection.addSeries(mXYSeries_duplicate);
			ChartPanel mChartPanel = new ChartPanel(mChart);
			frame.add(mChartPanel);
		}
		
	}

}
