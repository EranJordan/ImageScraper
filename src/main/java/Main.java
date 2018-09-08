import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;

import org.apache.tika.Tika;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import javax.imageio.ImageIO;
import java.net.MalformedURLException;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

public class Main {

    public static void main(String[] args) throws IOException {
        if (args.length != 2) { //check that input is valid
            System.out.println("Usage: [URL] [Output Folder]");
            System.exit(1);
        }

        String url = convertToValidURL(args[0]);
        String outputFolder = args[1];

        //TODO: Does not work with pages with bad security certificates. Don't know if this should be fixed.
        Document page = Jsoup.connect(url).get();

        //Open a file at output folder and a corresponding filewriter
        File outputFile = new File(outputFolder + "/index.html");
        outputFile.getParentFile().mkdirs();
        outputFile.createNewFile();
        FileWriter fileWriter = null;
        try {
            fileWriter = new FileWriter(outputFile);
        } catch (IOException e) {
            e.printStackTrace();
            System.err.println("Failed to open filewriter.");
        }
        //Get list of images from the HTML
        final List<?> images = page.getElementsByTag("img");

        //Builder for the html of the index file
        StringBuilder htmlBuilder = new StringBuilder();

        //Write the initial html. I will use \n since it works for linux and windows.
        //I set the background to be a light gray because a lot of sites use transparent white images that won't be visible otherwise.
        htmlBuilder.append("<!DOCTYPE html>\n<html>\n<head>\n<title> Scraped Images </title>\n</head>\n<body style=\"background-color:#d3d3d3\">\n<table>\n");

        //Go over all scraped images
        for (Object imageObject : images) {
            //Start a new table row in the html file
            htmlBuilder.append("<tr>\n");

            Element imageElem = (Element) imageObject;
            String src = imageElem.attr("src");
            int width, originalWidth;
            int height, originalHeight;

            if(imageElem.attr("width").equals("") || imageElem.attr("height").equals("")) { //deal with dynamic sized images
               //We'll have to load the image to check its dimensions
                URL imgURL = getURLFromSrc(src, url);
                BufferedImage image = ImageIO.read(imgURL);

                //If the image is null, then ImageIO failed to read. Possibly because the site denies the requests or unsupported type. (svg, webp)
                //If needed, libraries to support these formats can be added.
                if(image == null) {
                    System.err.println("Error reading image: " + src);
                    continue;
                }
                width = image.getWidth();
                height = image.getHeight();
            }
            else {
                width = Integer.parseInt(imageElem.attr("width"));
                height = Integer.parseInt(imageElem.attr("height"));
            }
            originalWidth = width;
            originalHeight = height;

            //If image is too wide, resize it
            if (width > 120) {
                height = resize(width, height);
                width = 120;
            }
            //Save image and get the filename for the src attribute of the html
            String fileName = downloadImage(src, url, outputFolder);
            System.out.println("Downloaded image: " + src);
            //Write image
            String imgTag = "<img src=\"" + fileName + "\" height=\"" + height +"\" width=\"" + width + "\"</image>";
            htmlBuilder.append("<td> " + imgTag + " </td>\n");

            //Create string holding information about the image, then write it
            String format = fileName.substring(fileName.lastIndexOf('.') + 1);
            String imageInfo = "URL: <a>" + getURLFromSrc(src, url) +"</a><br>\n Original Size: " + originalWidth + "x" + originalHeight + "<br>\n Format: " + format;
            htmlBuilder.append("<td> " + imageInfo + "</td>\n");

            htmlBuilder.append("</tr>\n");
        }

        //Write the end of the html file
        htmlBuilder.append("</table>\n</body>\n</html>");
        //Write to file
        fileWriter.write(htmlBuilder.toString());
        fileWriter.close();
        System.out.println("Scraped to: " + outputFolder);
        System.exit(0);
    }

    //Calculates the new height of the image if the width was larger than 120 and was resized.
    private static int resize(int width, int height) {
        double ratio = 120.0 / width;
        return (int) (height * ratio);
    }

    //Downloads image to given folder and returns the name it was assigned
    private static String downloadImage(String imageSrc, String source, String destination) throws IOException {
        String imageName =  imageSrc.substring(imageSrc.lastIndexOf('/') + 1); //The path is the output folder + the image name and format
        if(imageName.contains(".")) {
            imageName = imageName.substring(0, imageName.indexOf('.')); //Do not include the extension type, we will add it later.
        }

        //Create temp file from URL
        Path tmpFile = Files.createTempFile("image", null);

        URL url = getURLFromSrc(imageSrc, source);

        InputStream stream = url.openStream();
        Files.copy(stream, tmpFile, StandardCopyOption.REPLACE_EXISTING);

        //Get content type from the temp file. (Not all URLs contain the extension name, so we cannot get it that way)
        String format = getFormat(tmpFile);
        Files.delete(tmpFile);

        //Create path, removing illegal characters from filename
        String path = destination + "/" + imageName.replaceAll("\\W+", "_") + "." + format;

        File imageFile = new File(path);
        imageFile.createNewFile();
        FileUtils.copyURLToFile(url, imageFile);

        return imageFile.getName();
    }

    //Gets format from file
    private static String getFormat(Path imageFile) throws IOException {
       String type = new Tika().detect(imageFile);

       //Remove the 'image/' part as it is redundant in an image scraper
       return type.substring(type.indexOf("/")+1);
    }

    //Given the value of the src attribute, determines if it's an absolute/relative path and transforms it accordingly
    private static URL getURLFromSrc(String src, String siteUrl) throws MalformedURLException {
        URL url;

        int numOfPeriods = StringUtils.countMatches(src, ".");
        //If more than one period, we have an absolute address. Just use the src value.
        if(numOfPeriods > 1) {
            if(src.contains("http")) {
                url = new URL(src);
            }
            else {
                //src value is of the form //ADDRESS. add http: to make it a valid address
                url = new URL("http:"+src);
            }
        }

        //If relative url, add website url
        else {
            //Remove extra /, as images are found under the main domain. There should only be 2 from https://
            int numOfSlashes = StringUtils.countMatches(siteUrl, "/");
            if(numOfSlashes > 2) {
                //Keep only the part before the third slash
                siteUrl = siteUrl.substring(0, StringUtils.ordinalIndexOf(siteUrl, "/", 3));
            }
            url =  new URL(siteUrl + src);
        }
        return url;
    }

    private static String convertToValidURL(String url) {
        //first, try converting to URI and see if it's valid.
        try {
            new URL(url).toURI();
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("invalid URL");
            System.exit(2);
        }
        //Jsoup only works with https sites
        url = url.replace("http://", "https://");

        //some sites (such as google) can't find images when you don't include www, so check and add if necessary.
        if(!url.startsWith("https://www.")) {
           url = url.replace("https://", "https://www.");
        }
        return url;
    }
}