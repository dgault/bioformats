

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.OpenDialog;
import ij.plugin.PlugIn;
import ij.process.ImageProcessor;
import ij.process.LUT;
import java.io.IOException;

import loci.common.services.DependencyException;
import loci.common.services.ServiceException;
import loci.common.services.ServiceFactory;
import loci.formats.ChannelSeparator;
import loci.formats.FormatException;
import loci.formats.IFormatReader;
import loci.formats.ImageWriter;
import loci.formats.in.MetamorphReader;
import loci.formats.meta.IMetadata;
import loci.formats.meta.MetadataRetrieve;
import loci.formats.meta.MetadataStore;
import loci.formats.out.OMETiffWriter;
import loci.formats.out.TiffWriter;
import loci.formats.services.OMEXMLService;
import loci.plugins.util.ImageProcessorReader;
import loci.plugins.util.LociPrefs;

import ome.xml.model.primitives.PositiveInteger;

/**
 * An ImageJ plugin that uses Bio-Formats to build up an {@link ImageStack},
 * reading image planes one by one.
 */
public class Read_Image implements PlugIn {
  public void run(String arg) {
    OpenDialog od = new OpenDialog("Open Image File...", arg);
    String dir = od.getDirectory();
    String name = od.getFileName();
    String id = dir + name;
    MetamorphReader reader = new MetamorphReader();

    try {
      IJ.showStatus("Examining file " + name);
      ServiceFactory factory = new ServiceFactory();
      OMEXMLService service = factory.getInstance(OMEXMLService.class);
      IMetadata omexmlMeta = service.createOMEXMLMetadata();
      reader.setMetadataStore(omexmlMeta);
      reader.setId(id);
      
      // Modify the size of z and time points 
      int sizeZ = reader.getSizeZ();
      int sizeT = reader.getSizeT();
      omexmlMeta.setPixelsSizeZ(new PositiveInteger(1), 0);
      omexmlMeta.setPixelsSizeT(new PositiveInteger(sizeZ * sizeT), 0);
      
      // Write out the new modified file
      OMETiffWriter writer = new OMETiffWriter();
      writer.setMetadataRetrieve(omexmlMeta);
      IJ.showStatus("Writing file " + dir  + "test_modified.ome.tif");
      writer.setId(dir  + "test_modified.tif");
      for (int series=0; series<reader.getSeriesCount(); series++) {
        reader.setSeries(series);
        writer.setSeries(series);
        for (int image=0; image<reader.getImageCount(); image++) {
          writer.saveBytes(image, reader.openBytes(image));
        }
      }
      writer.close();
      reader.close();
      
      // Open open the modified file in ImageJ
      ImageProcessorReader r = new ImageProcessorReader(
          new ChannelSeparator(LociPrefs.makeImageReader()));
      r.setId(dir  + "test_modified.tif");
      int num = reader.getImageCount();
      int width = reader.getSizeX();
      int height = reader.getSizeY();
      ImageStack stack = new ImageStack(width, height);
      byte[][][] lookupTable = new byte[r.getSizeC()][][];
      for (int i=0; i<num; i++) {
        IJ.showStatus("Reading image plane #" + (i + 1) + "/" + num);
        ImageProcessor ip = r.openProcessors(i)[0];
        stack.addSlice("" + (i + 1), ip);
        int channel = r.getZCTCoords(i)[1];
        lookupTable[channel] = r.get8BitLookupTable();
      }
      IJ.showStatus("Constructing image");
      ImagePlus imp = new ImagePlus(name, stack);

      ImagePlus colorizedImage = applyLookupTables(r, imp, lookupTable);
      r.close();

      colorizedImage.show();
      IJ.showStatus("");
    }
    catch (FormatException exc) {
      IJ.error("Sorry, an error occurred: " + exc.getMessage());
    }
    catch (IOException exc) {
      IJ.error("Sorry, an error occurred: " + exc.getMessage());
    } catch (DependencyException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    } catch (ServiceException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  private ImagePlus applyLookupTables(IFormatReader r, ImagePlus imp,
    byte[][][] lookupTable)
  {
    // apply color lookup tables, if present
    // this requires ImageJ v1.39 or higher
    if (r.isIndexed()) {
      CompositeImage composite =
        new CompositeImage(imp, CompositeImage.COLOR);
      for (int c=0; c<r.getSizeC(); c++) {
        composite.setPosition(c + 1, 1, 1);
        LUT lut =
          new LUT(lookupTable[c][0], lookupTable[c][1], lookupTable[c][2]);
        composite.setChannelLut(lut);
      }
      composite.setPosition(1, 1, 1);
      return composite;
    }
    return imp;
  }
}
