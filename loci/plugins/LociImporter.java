//
// LociImporter.java
//

/*
LOCI Plugins for ImageJ: a collection of ImageJ plugins including the 4D
Data Browser, OME Plugin and Bio-Formats Exporter. Copyright (C) 2006
Melissa Linkert, Christopher Peterson, Curtis Rueden, Philip Huettl
and Francis Wong.

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU Library General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Library General Public License for more details.

You should have received a copy of the GNU Library General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package loci.plugins;

import ij.*;
import ij.gui.GenericDialog;
import ij.io.FileInfo;
import ij.io.OpenDialog;
import ij.measure.Calibration;
import ij.plugin.PlugIn;
import ij.process.*;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.image.*;
import java.io.File;
import java.util.Hashtable;
import javax.swing.*;
import loci.formats.*;

/**
 * ImageJ plugin for reading files using the LOCI Bio-Formats package.
 *
 * @author Curtis Rueden ctrueden at wisc.edu
 * @author Melissa Linkert linkert at cs.wisc.edu
 */
public class LociImporter implements PlugIn, ItemListener {

  // -- Static fields --

  /** Flag indicating whether last operation was successful. */
  public boolean success = false;

  private JCheckBox merge = null;
  private JCheckBox newWindows = null;
  private JCheckBox showMeta = null;
  private JCheckBox stitching = null;
  private JCheckBox ranges = null;
  private boolean mergeChannels;
  private boolean splitWindows;
  private boolean showMetadata;
  private boolean stitchFiles;
  private boolean specifyRanges;

  // -- PlugIn API methods --

  /** Executes the plugin. */
  public synchronized void run(String arg) {
    success = false;
    if (!Util.checkVersion()) return;
    if (!Util.checkLibraries(true, true, false)) return;

    // load preferences from IJ_Prefs.txt
    mergeChannels = Prefs.get("bioformats.mergeChannels", true);
    splitWindows = Prefs.get("bioformats.splitWindows", false);
    showMetadata = Prefs.get("bioformats.showMetadata", false);
    stitchFiles = Prefs.get("bioformats.stitchFiles", false);
    specifyRanges = Prefs.get("bioformats.specifyRanges", false);

    boolean quiet = !"".equals(arg);

    String id = null;
    ImageReader reader = new ImageReader();

    String mergeString = "Merge channels";
    String splitString = "Open each channel in its own window";
    String metadataString = "Display associated metadata";
    String stitchString = "Stitch files with similar names";
    String rangeString = "Specify range for each series";
    if ((new File(arg)).exists()) {
      id = arg;

      // we still want to prompt for channel merge/split
      GenericDialog gd = new GenericDialog("LOCI Bio-Formats Import Options");
      gd.addCheckbox(mergeString, mergeChannels);
      gd.addCheckbox(splitString, splitWindows);
      gd.addCheckbox(metadataString, showMetadata);
      gd.addCheckbox(stitchString, stitchFiles);
      gd.addCheckbox(rangeString, specifyRanges);
      gd.showDialog();
      if (gd.wasCanceled()) return;
      mergeChannels = gd.getNextBoolean();
      splitWindows = gd.getNextBoolean();
      showMetadata = gd.getNextBoolean();
      stitchFiles = gd.getNextBoolean();
      specifyRanges = gd.getNextBoolean();
    }
    else {
      // use system L&F, to match ImageJ's appearance to the extent possible
      LookAndFeel laf = null;
      try {
        laf = UIManager.getLookAndFeel();
        String sys = UIManager.getSystemLookAndFeelClassName();
        UIManager.setLookAndFeel(sys);
      }
      catch (Exception exc) { exc.printStackTrace(); }

      JFileChooser chooser = reader.getFileChooser();
      String dir = OpenDialog.getDefaultDirectory();
      if (dir != null) chooser.setCurrentDirectory(new File(dir));

      // add some additional options to the file chooser
      JPanel panel = new JPanel();
      panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
      merge = new JCheckBox(mergeString, mergeChannels);
      newWindows = new JCheckBox(splitString, splitWindows);
      showMeta = new JCheckBox(metadataString, showMetadata);
      stitching = new JCheckBox(stitchString, stitchFiles);
      ranges = new JCheckBox(rangeString, specifyRanges);
      merge.addItemListener(this);
      newWindows.addItemListener(this);
      showMeta.addItemListener(this);
      stitching.addItemListener(this);
      ranges.addItemListener(this);
      panel.add(merge);
      panel.add(newWindows);
      panel.add(showMeta);
      panel.add(stitching);
      panel.add(ranges);
      chooser.setAccessory(panel);

      int rval = chooser.showOpenDialog(null);

      // restore original L&F
      if (laf != null) {
        try { UIManager.setLookAndFeel(laf); }
        catch (UnsupportedLookAndFeelException exc) { exc.printStackTrace(); }
      }

      if (rval == JFileChooser.APPROVE_OPTION) {
        final File file = chooser.getSelectedFile();
        if (file != null) {
          id = file.getAbsolutePath();
          OpenDialog.setDefaultDirectory(
            chooser.getCurrentDirectory().getPath());
        }
      }
    }

    if (id == null) return;
    String fileName = id.substring(id.lastIndexOf(File.separator) + 1);

    IJ.showStatus("Opening " + fileName);
    try {
      FormatReader r = (FormatReader) reader.getReader(id);
      if (stitchFiles) r = new FileStitcher(r);
      if (mergeChannels) r = new ChannelMerger(r);
      else r = new ChannelSeparator(r);

      // store OME metadata into OME-XML structure, if available
      OMEXMLMetadataStore store = new OMEXMLMetadataStore();
      store.createRoot();
      r.setMetadataStore(store);

      // if necessary, prompt for the series to open

      GenericDialog datasets =
        new GenericDialog("LOCI Bio-Formats Series Chooser");

      int seriesCount = r.getSeriesCount(id);
      store = (OMEXMLMetadataStore) r.getMetadataStore(id);
      for (int i=0; i<seriesCount; i++) {
        r.setSeries(id, i);
        int sizeX = r.getSizeX(id);
        int sizeY = r.getSizeY(id);
        int imageCount = r.getImageCount(id);
        String name = store.getImageName(new Integer(i));
        if (name == null || name.length() == 0) {
          name = "Series " + (i + 1);
        }
        datasets.addCheckbox(name + " : " + sizeX + " x " + sizeY + " x " +
          imageCount, i == 0);
      }
      if (seriesCount > 1) {
        datasets.showDialog();
        if (datasets.wasCanceled()) return;
      }

      boolean[] series = new boolean[seriesCount];
      for (int i=0; i<seriesCount; i++) series[i] = datasets.getNextBoolean();

      if (showMetadata) {
        // display standard metadata in a table in its own window
        Hashtable meta = r.getMetadata(id);
        MetadataPane mp = new MetadataPane(meta);
        JFrame frame = new JFrame(id + " Metadata");
        frame.setContentPane(mp);
        frame.pack();
        frame.setVisible(true);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
      }

      for (int i=0; i<series.length; i++) {
        if (!series[i]) continue;
        r.setSeries(id, i);

        int num = r.getImageCount(id);
        int begin = 0, end = num - 1, step = 1;
        if (specifyRanges && num > 1) {
          // prompt for range of image planes to import
          GenericDialog range =
            new GenericDialog("LOCI Bio-Formats Range Chooser");
          range.addMessage("Series " + (i + 1) + ": " + num + " planes");
          range.addNumericField("Series_" + (i + 1) + "_Begin: ", 1, 0);
          range.addNumericField("Series_" + (i + 1) + "_End: ", num, 0);
          range.addNumericField("Series_" + (i + 1) + "_Step: ", 1, 0);
          range.showDialog();
          if (range.wasCanceled()) continue;
          begin = (int) range.getNextNumber() - 1;
          end = (int) range.getNextNumber() - 1;
          step = (int) range.getNextNumber();
        }
        if (begin < 0) begin = 0;
        if (begin >= num) begin = num - 1;
        if (end < begin) end = begin;
        if (end >= num) end = num - 1;
        if (step < 1) step = 1;
        int total = (end - begin) / step + 1;

        // dump OME-XML to ImageJ's description field, if available
        FileInfo fi = new FileInfo();
        fi.description = store.dumpXML();

        long startTime = System.currentTimeMillis();
        long time = startTime;
        ImageStack stackB = null, stackS = null, stackF = null, stackO = null;
        int channels = r.getSizeC(id);

        int q = 0;
        for (int j=begin; j<=end; j+=step) {
          // limit message update rate
          long clock = System.currentTimeMillis();
          if (clock - time >= 50) {
            IJ.showStatus("Reading plane " + (j + 1) + "/" + (end + 1));
            time = clock;
          }
          IJ.showProgress((double) q++ / total);
          BufferedImage img = r.openImage(id, j);
          img = ImageTools.padImage(img, r.getSizeX(id), r.getSizeY(id));

          ImageProcessor ip = null;
          WritableRaster raster = img.getRaster();
          int c = raster.getNumBands();
          int tt = raster.getTransferType();
          int w = img.getWidth(), h = img.getHeight();
          if (c == 1) {
            if (tt == DataBuffer.TYPE_BYTE) {
              byte[] b = ImageTools.getBytes(img)[0];
              if (b.length > w*h) {
                byte[] tmp = b;
                b = new byte[w*h];
                System.arraycopy(tmp, 0, b, 0, b.length);
              }
              ip = new ByteProcessor(w, h, b, null);
              if (stackB == null) stackB = new ImageStack(w, h);
              stackB.addSlice(fileName + ":" + (j + 1), ip);
            }
            else if (tt == DataBuffer.TYPE_USHORT) {
              short[] s = ImageTools.getShorts(img)[0];
              if (s.length > w*h) {
                short[] tmp = s;
                s = new short[w*h];
                System.arraycopy(tmp, 0, s, 0, s.length);
              }
              ip = new ShortProcessor(w, h, s, null);
              if (stackS == null) stackS = new ImageStack(w, h);
              stackS.addSlice(fileName + ":" + (j + 1), ip);
            }
            else if (tt == DataBuffer.TYPE_FLOAT) {
              float[] f = ImageTools.getFloats(img)[0];
              if (f.length > w*h) {
                float[] tmp = f;
                f = new float[w*h];
                System.arraycopy(tmp, 0, f, 0, f.length);
              }
              ip = new FloatProcessor(w, h, f, null);
              if (stackF == null) stackF = new ImageStack(w, h);
              stackF.addSlice(fileName + ":" + (j + 1), ip);
            }
          }
          if (ip == null) {
            ip = new ImagePlus(null, img).getProcessor(); // slow
            if (stackO == null) stackO = new ImageStack(w, h);
            stackO.addSlice(fileName + ":" + (j + 1), ip);
          }
        }
        IJ.showStatus("Creating image");
        IJ.showProgress(1);
        ImagePlus imp = null;
        if (stackB != null) {
          if (!mergeChannels && splitWindows) {
            slice(stackB, fileName, channels, fi);
          }
          else imp = new ImagePlus(fileName, stackB);
        }
        if (stackS != null) {
          if (!mergeChannels && splitWindows) {
            slice(stackS, fileName, channels, fi);
          }
          else imp = new ImagePlus(fileName, stackS);
        }
        if (stackF != null) {
          if (!mergeChannels && splitWindows) {
            slice(stackF, fileName, channels, fi);
          }
          else imp = new ImagePlus(fileName, stackF);
        }
        if (stackO != null) {
          if (!mergeChannels && splitWindows) {
            slice(stackO, fileName, channels, fi);
          }
          else imp = new ImagePlus(fileName, stackO);
        }

        if (imp != null) {
          // retrieve the spatial calibration information, if available

          double xcal = Double.NaN, ycal = Double.NaN, zcal = Double.NaN;
          Integer ii = new Integer(i);

          Float xf = store.getPixelSizeX(ii);
          if (xf != null) xcal = xf.floatValue();
          Float yf = store.getPixelSizeY(ii);
          if (yf != null) ycal = yf.floatValue();
          Float zf = store.getPixelSizeZ(ii);
          if (zf != null) zcal = zf.floatValue();

          Calibration c = new Calibration();
          c.pixelWidth = xcal;
          c.setUnit("micron");
          c.pixelHeight = ycal;
          c.setUnit("micron");
          c.pixelDepth = zcal;
          c.setUnit("micron");
          imp.setCalibration(c);

          imp.setFileInfo(fi);
          imp.show();
        }

        long endTime = System.currentTimeMillis();
        double elapsed = (endTime - startTime) / 1000.0;
        if (num == 1) {
          IJ.showStatus("LOCI Bio-Formats: " + elapsed + " seconds");
        }
        else {
          long average = (endTime - startTime) / num;
          IJ.showStatus("LOCI Bio-Formats: " + elapsed + " seconds (" +
            average + " ms per plane)");
        }
      }
      success = true;

      // save checkbox values to IJ_Prefs.txt

      Prefs.set("bioformats.mergeChannels", mergeChannels);
      Prefs.set("bioformats.splitWindows", splitWindows);
      Prefs.set("bioformats.showMetadata", showMetadata);
      Prefs.set("bioformats.stitchFiles", stitchFiles);
      Prefs.set("bioformats.specifyRanges", specifyRanges);

      mergeChannels = true;
      splitWindows = false;
    }
    catch (Exception exc) {
      exc.printStackTrace();
      IJ.showStatus("");
      if (!quiet) {
        String msg = exc.getMessage();
        IJ.showMessage("LOCI Bio-Formats", "Sorry, there was a problem " +
          "reading the data" + (msg == null ? "." : (": " + msg)));
      }
    }
  }

  private void slice(ImageStack is, String file, int c, FileInfo fi) {
    ImageStack[] newStacks = new ImageStack[c];
    for (int i=0; i<newStacks.length; i++) {
      newStacks[i] = new ImageStack(is.getWidth(), is.getHeight());
    }

    for (int i=1; i<=is.getSize(); i+=c) {
      for (int j=0; j<c; j++) {
        newStacks[j].addSlice(is.getSliceLabel(i+j), is.getProcessor(i+j));
      }
    }

    for (int i=0; i<newStacks.length; i++) {
      ImagePlus imp = new ImagePlus(file + " - Ch" + (i+1), newStacks[i]);
      imp.setFileInfo(fi);
      imp.show();
    }
  }

  public void itemStateChanged(ItemEvent e) {
    Object source = e.getItemSelectable();
    boolean selected = e.getStateChange() == ItemEvent.SELECTED;
    if (source == merge) mergeChannels = selected;
    else if (source == newWindows) splitWindows = selected;
    else if (source == showMeta) showMetadata = selected;
    else if (source == stitching) stitchFiles = selected;
    else if (source == ranges) specifyRanges = selected;
  }

}
