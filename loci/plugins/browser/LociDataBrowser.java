//
// LociDataBrowser.java
//

/*
LOCI 4D Data Browser plugin for quick browsing of 4D datasets in ImageJ.
Copyright (C) 2005-@year@ Christopher Peterson, Francis Wong, Curtis Rueden
and Melissa Linkert.

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

package loci.plugins.browser;

import ij.*;
import ij.gui.GenericDialog;
import ij.gui.ImageCanvas;
import ij.io.FileInfo;
import ij.plugin.PlugIn;
import java.io.File;
import loci.formats.*;
import loci.plugins.Util;

/**
 * LociDataBrowser is a plugin for ImageJ that allows for browsing of 4D
 * image data (stacks of image planes over time) with two-channel support.
 *
 * @author Francis Wong yutaiwong at wisc.edu
 * @author Curtis Rueden ctrueden at wisc.edu
 * @author Melissa Linkert linkert at cs.wisc.edu
 */
public class LociDataBrowser implements PlugIn {

  // -- Constants --

  /** Debugging flag. */
  protected static final boolean DEBUG = false;

  // -- Fields --

  /** Filename for each index. */
  protected String[] names;

  /** The file format reader used by the plugin. */
  protected ImageReader reader = new ImageReader();

  /** The current file name. */
  protected String filename;

  /** Whether dataset has multiple Z, T and C positions. */
  protected boolean hasZ, hasT, hasC;

  /** Number of Z, T and C positions. */
  protected int numZ, numT, numC;

  /** Lengths of all dimensional axes; lengths[0] equals image depth. */
  protected int[] lengths;

  /** Indices into lengths array for Z, T and C. */
  protected int zIndex, tIndex, cIndex;

  /** Whether stack is accessed from disk as needed. */
  protected boolean virtual;

  /** Cache manager (if virtual stack is used). */
  protected CacheManager manager;

  /** Series to use in a multi-series file. */
  protected int series;

  private ImageStack stack;

  // -- LociDataBrowser methods --

  /** Displays the given ImageJ image in a 4D browser window. */
  public void show(ImagePlus imp) {
    int stackSize = imp == null ? 0 : imp.getStackSize();

    if (stackSize == 0) {
      IJ.showMessage("Cannot show invalid image.");
      return;
    }

    if (stackSize == 1) {
      // show single image normally
      imp.show();
      return;
    }

    new CustomWindow(this, imp, new ImageCanvas(imp));
  }

  /** Set the length of each dimensional axis and the dimension order. */
  public void setDimensions(int sizeZ, int sizeC, int sizeT, int z,
    int c, int t)
  {
    numZ = sizeZ;
    numC = sizeC;
    numT = sizeT;

    hasZ = numZ > 1;
    hasC = numC > 1;
    hasT = numT > 1;

    lengths = new int[3];
    lengths[z] = numZ;
    lengths[c] = numC;
    lengths[t] = numT;

    zIndex = z;
    cIndex = c;
    tIndex = t;
  }

  /** Gets the slice number for the given Z, T and C indices. */
  public int getIndex(int z, int t, int c) {
    int[] pos = new int[lengths.length];
    if (zIndex >= 0) pos[zIndex] = z;
    if (tIndex >= 0) pos[tIndex] = t;
    if (cIndex >= 0) pos[cIndex] = c;
    int[] offsets = new int[lengths.length];
    if (offsets.length > 0) offsets[0] = 1;
    for (int i=1; i<offsets.length; i++) {
      offsets[i] = offsets[i - 1] * lengths[i - 1];
    }
    int raster = 0;
    for (int i=0; i<pos.length; i++) raster += offsets[i] * pos[i];
    return raster;
  }

  /** Sets the series to open. */
  public void setSeries(int num) {
    // TODO : this isn't the prettiest way of prompting for a series
    GenericDialog datasets =
      new GenericDialog("4D Data Browser Series Chooser");

    String[] values = new String[num];
    for (int i=0; i<values.length; i++) values[i] = "" + i;

    datasets.addChoice("Series ", values, "0");

    if (num > 1) datasets.showDialog();

    series = Integer.parseInt(datasets.getNextChoice());
  }

  // -- Plugin methods --

  public void run(String arg) {
    if (!Util.checkVersion()) return;
    if (!Util.checkLibraries(true, true, false)) return;

    String version = System.getProperty("java.version");
    double ver = Double.parseDouble(version.substring(0, 3));
    if (ver < 1.4) {
      IJ.showMessage("Sorry, the 4D Data Browser requires\n" +
        "Java 1.4 or later. You can download ImageJ\n" +
        "with JRE 5.0 from the ImageJ web site.");
      return;
    }
    LociOpener lociOpener = new LociOpener();
    boolean done2 = false;
    String directory = "";
    String name = "";
    boolean quiet = false;
    // get file name and virtual stack option
    stack = null;
    while (!done2) {
      try {
        lociOpener.show();
        directory = lociOpener.getDirectory();
        name = lociOpener.getAbsolutePath();
        virtual = lociOpener.getVirtual();
        if (name == null || lociOpener.isCanceled()) return;
        if (DEBUG) {
          IJ.log("directory = " + directory);
          IJ.log("name = " + name);
          IJ.log("virtual = " + virtual);
        }
        ImagePlusWrapper ipw = null;

        // process input
        lengths = new int[3];

        String absname = name;
        filename = absname;
        name = FilePattern.findPattern(new File(name));
        name = name.substring(name.lastIndexOf(File.separatorChar)+1);
        if (DEBUG) System.err.println("name = "+name);

        if (virtual) {
          FormatReader fr = (FormatReader) reader.getReader(absname);
          fr.setMetadataStore(new OMEXMLMetadataStore());
          fr.getMetadataStore(absname).createRoot();
          FileStitcher fs = new FileStitcher(fr);
          ChannelMerger cm = new ChannelMerger(fs);
          setSeries(cm.getSeriesCount(absname));
          cm.setSeries(absname, series);

          int num = cm.getImageCount(absname);

          int size = 20;
          if (num < size) size = num;

          String ord = cm.getDimensionOrder(absname);
          ord = ord.substring(2);
          int minor, major;
          if (ord.charAt(0) == 'Z') {
            minor = reader.getSizeZ(filename);
            major = reader.getSizeT(filename);
          }
          else if (ord.charAt(0) == 'T') {
            major = reader.getSizeZ(filename);
            minor = reader.getSizeT(filename);
          }
          else {
            if (ord.charAt(1) == 'Z') {
              minor = reader.getSizeZ(filename);
              major = reader.getSizeT(filename);
            }
            else {
              major = reader.getSizeZ(filename);
              minor = reader.getSizeT(filename);
            }
          }

          //manager = new CacheManager(size, minor, major, cm, absname);
          manager = new CacheManager(size, cm, absname);

          try {
            numZ = cm.getSizeZ(absname);
            numC = cm.getSizeC(absname);
            if (cm.isRGB(absname)) {
              if (numC <= 3) numC = 1;
              else numC /= 3;
            }
            numT = cm.getSizeT(absname);
            hasZ = numZ > 1;
            hasC = numC > 1;
            hasT = numT > 1;

            String order = cm.getDimensionOrder(absname);
            zIndex = order.indexOf("Z") - 2;
            cIndex = order.indexOf("C") - 2;
            tIndex = order.indexOf("T") - 2;

            lengths[zIndex] = numZ;
            lengths[tIndex] = numT;
            lengths[cIndex] = numC;

            stack = new ImageStack(cm.getSizeX(absname), cm.getSizeY(absname));

            for (int i=0; i<size; i++) {
              stack.addSlice(absname + " : " + (i+1), manager.getSlice(0,i,0));
            }
            

            if (stack == null || stack.getSize() == 0) {
              IJ.showMessage("No valid files found.");
              return;
            }
          }
          catch (OutOfMemoryError e) {
            IJ.outOfMemory("LociDataBrowser");
            if (stack != null) stack.trim();
          }

          ImagePlus ip = new ImagePlus(absname, stack);
          FileInfo fi = new FileInfo();
          try {
            fi.description =
              ((OMEXMLMetadataStore) fr.getMetadataStore(absname)).dumpXML();
          }
          catch (Exception e) { }

          ip.setFileInfo(fi);
          show(ip);
        }
        else {
          ipw = new ImagePlusWrapper(absname, reader.getReader(name), true);
          numZ = ipw.sizeZ; numT = ipw.sizeT; numC = ipw.sizeC;
          zIndex = ipw.dim.indexOf('Z') - 2;
          tIndex = ipw.dim.indexOf('T') - 2;
          cIndex = ipw.dim.indexOf('C') - 2;

          if (ipw.getImagePlus().getStackSize() != numZ * numT * numC) {
            numC = 1;
          }

          lengths[zIndex] = numZ;
          lengths[tIndex] = numT;
          lengths[cIndex] = numC;

          hasZ = numZ > 1;
          hasT = numT > 1;
          hasC = numC > 1;

          FileInfo fi = ipw.getImagePlus().getOriginalFileInfo();
          if (fi == null) fi = new FileInfo();
          try {
            fi.description = ((OMEXMLMetadataStore) ipw.store).dumpXML();
          }
          catch (Exception e) { }
          ipw.getImagePlus().setFileInfo(fi);

          show(ipw.getImagePlus());
        }
        done2 = true;
      }
      catch (Exception exc) {
        exc.printStackTrace();
        IJ.showStatus("");
        if (!quiet) {
          String msg = exc.getMessage();
          IJ.showMessage("LOCI Bio-Formats", "Sorry, there was a problem " +
            "reading the data" + (msg == null ? "." : (": " + msg)));
        }
        if (DEBUG) System.err.println("Read error");
        done2 = false;
      }
    }
  }

  /** Main method, for testing. */
  public static void main(String[] args) {
    new ImageJ(null);
    new LociDataBrowser().run("");
  }

}
