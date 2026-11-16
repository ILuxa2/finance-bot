package financeos.vk;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/** Keep even previously queued chart bundles inside a single VK message (10 attachments). */
final class ChartAttachments {
    static List<byte[]> fit(List<byte[]> images) throws IOException {
        if(images.size()<=10)return images;
        int groupSize=(images.size()+9)/10;
        var result=new ArrayList<byte[]>();
        for(int offset=0;offset<images.size();offset+=groupSize) {
            var decoded=new ArrayList<BufferedImage>();int width=0,height=0;
            for(var bytes:images.subList(offset,Math.min(offset+groupSize,images.size()))) {
                var image=ImageIO.read(new ByteArrayInputStream(bytes));
                if(image==null)throw new IOException("Invalid chart image");
                width=Math.max(width,image.getWidth());height=Math.addExact(height,image.getHeight());decoded.add(image);
            }
            if((long)width*height>40_000_000L)throw new IOException("Chart composite is too large");
            var composite=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
            var g=composite.createGraphics();
            try {g.setColor(new Color(decoded.getFirst().getRGB(0,0)));g.fillRect(0,0,width,height);
                int y=0;for(var image:decoded){g.drawImage(image,0,y,null);y+=image.getHeight();}
            } finally {g.dispose();}
            var out=new ByteArrayOutputStream();ImageIO.write(composite,"png",out);result.add(out.toByteArray());
        }
        return List.copyOf(result);
    }
}
