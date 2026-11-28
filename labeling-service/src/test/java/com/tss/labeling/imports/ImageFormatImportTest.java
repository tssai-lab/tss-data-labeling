package com.tss.labeling.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ImageFormatImportTest {
    @TempDir Path root;

    static byte[] fixture(String name) throws Exception {
        try (var input=ImageFormatImportTest.class.getResourceAsStream("/images/"+name)) {
            assertNotNull(input,name);return input.readAllBytes();
        }
    }
    ImportSettings settings(long bytes,int entries,long pixels) {
        return new ImportSettings(root.toString(),256,1048576,bytes,1048576,entries,pixels,10,"UTF-8",false);
    }
    ArchiveParser.ParsedArchive parse(String path,byte[] bytes,String task,long budget,int entries,long pixels)throws Exception {
        var settings=settings(budget,entries,pixels);var storage=new ImportStorage(settings);UUID batch=UUID.randomUUID();
        Files.write(storage.archive(batch),ImageTestFiles.zip(Map.of(path,bytes)));
        return new ArchiveParser(settings,storage,new NativeInputParser(new ObjectMapper())).parse(batch,task);
    }
    @ParameterizedTest
    @CsvSource({"图像.TIFF,sample.tiff,image/tiff", "图像.tif,sample.tiff,image/tiff", "图像.WeBp,sample-lossless.webp,image/webp",
            "lossy.webp,sample-lossy.webp,image/webp", "图像.BMP,sample.bmp,image/bmp"})
    void importsRealFormatsPreservesSourceAndServesSameSizePng(String name,String fixture,String media)throws Exception {
        byte[] bytes=fixture(fixture);
        for(String type:List.of("IMAGE_CLASSIFICATION","COMPUTER_VISION")) {
            var parsed=parse(name,bytes,type,1048576,20,10000);
            assertEquals(0,parsed.errorCount(),parsed.errors().toString());
            var sample=(type.equals("IMAGE_CLASSIFICATION")?parsed.samples():parsed.resources()).get(0);
            UUID batch=UUID.fromString(sample.storageKey().split("/")[0]);var storage=new ImportStorage(settings(1048576,20,10000));
            assertEquals(media,sample.mediaType());assertArrayEquals(bytes,Files.readAllBytes(storage.sample(batch,sample.id())));
            assertEquals(ImageTestFiles.sha(bytes),sample.sha256());
            var display=storage.display(batch,sample.id(),media,bytes.length);
            assertEquals("image/png",display.mediaType());assertEquals(display.sizeBytes(),display.resource().contentLength());
            var png=ImageIO.read(display.resource().getFile());assertEquals(8,png.getWidth());assertEquals(6,png.getHeight());
            assertEquals(sample.metadata().get("previewSha256"),ImportStorage.sha256(display.resource().getFile().toPath()));
            if(fixture.equals("sample-lossless.webp"))assertEquals(128,png.getRGB(0,0)>>>24);
        }
    }
    @Test void tiffPagesHaveDistinctIdentityDimensionsAndStableLocalReferences()throws Exception {
        byte[] bytes=fixture("two-pages.tiff");
        var parsed=parse("中文/扫描.TIFF",bytes,"COMPUTER_VISION",1048576,20,10000);
        assertEquals(0,parsed.errorCount(),parsed.errors().toString());assertEquals(2,parsed.resources().size());
        var first=parsed.resources().get(0);var second=parsed.resources().get(1);
        assertEquals("中文/扫描.TIFF",first.sourcePath());assertEquals("中文/扫描.TIFF#page-2",second.sourcePath());
        assertNotEquals(first.id(),second.id());assertEquals(0,first.metadata().get("pageIndex"));assertEquals(1,second.metadata().get("pageIndex"));
        assertEquals(2,second.metadata().get("pageCount"));assertEquals(5,second.metadata().get("width"));assertEquals(4,second.metadata().get("height"));
        assertEquals(first.sha256(),second.sha256());
        var storage=new ImportStorage(settings(1048576,20,10000));UUID batch=UUID.fromString(second.storageKey().split("/")[0]);
        var png=ImageIO.read(storage.display(batch,second.id(),second.mediaType(),bytes.length).resource().getFile());
        assertEquals(5,png.getWidth());assertEquals(4,png.getHeight());assertEquals(0xff0ac81e,png.getRGB(0,0));
        assertTrue(ImageFormats.isReference(second.sourcePath()));assertFalse(ImageFormats.isReference("x.tiff#page-0"));
        var task=new ObjectMapper().readTree("{\"data\":{\"image\":\"中文/扫描.TIFF#page-2\"}}");
        assertEquals("扫描.TIFF#page-2",new NativeInputParser(new ObjectMapper()).singleSourceFileName(task));
    }
    @Test void checksActualFormatAndPixelLimitsAndRejectsBrokenFiles()throws Exception {
        var forged=parse("forged.tiff",ImageTestFiles.image("png"),"IMAGE_CLASSIFICATION",1048576,20,10000);
        assertEquals("INVALID_IMAGE",forged.errors().get(0).code());assertTrue(forged.samples().isEmpty());
        var broken=parse("broken.webp",new byte[]{1,2,3},"COMPUTER_VISION",1048576,20,10000);
        assertEquals("INVALID_IMAGE",broken.errors().get(0).code());assertTrue(broken.resources().isEmpty());
        var oversized=parse("large.bmp",fixture("sample.bmp"),"IMAGE_CLASSIFICATION",1048576,20,10);
        assertEquals("IMAGE_TOO_LARGE",oversized.errors().get(0).code());
    }
    @Test void boundsDerivedFilesAndPageExpansionAndCleansFailedImages()throws Exception {
        byte[] bytes=fixture("two-pages.tiff");
        var tooMany=assertThrows(ArchiveParser.Rejected.class,()->parse("pages.tiff",bytes,"COMPUTER_VISION",1048576,1,10000));
        assertEquals("EXPANSION_LIMIT",tooMany.code);
        var tooLarge=assertThrows(ArchiveParser.Rejected.class,()->parse("pages.tiff",bytes,"IMAGE_CLASSIFICATION",bytes.length,20,10000));
        assertEquals("EXPANSION_LIMIT",tooLarge.code);
        try(var files=Files.walk(root)){assertEquals(0,files.filter(path->path.getFileName().toString().endsWith(".preview.png")).count());}
    }
    @Test void rejectsIncompleteLaterTiffPageWithoutPublishingFirstPage()throws Exception {
        byte[] bytes=fixture("two-pages.tiff");
        var parsed=parse("truncated.tiff",Arrays.copyOf(bytes,bytes.length-30),"IMAGE_CLASSIFICATION",1048576,20,10000);
        assertEquals(1,parsed.errorCount());assertTrue(parsed.samples().isEmpty());
        try(var files=Files.walk(root)){assertEquals(0,files.filter(path->path.getFileName().toString().endsWith(".preview.png")).count());}
    }
    @Test void legacyPngJpegRemainByteIdenticalAndMissingPreviewFailsClosed()throws Exception {
        for(String format:List.of("png","jpg")) {
            byte[] bytes=ImageTestFiles.image(format);var parsed=parse("image."+format,bytes,"IMAGE_CLASSIFICATION",1048576,20,10000);
            var sample=parsed.samples().get(0);UUID batch=UUID.fromString(sample.storageKey().split("/")[0]);
            var storage=new ImportStorage(settings(1048576,20,10000));
            assertArrayEquals(bytes,storage.display(batch,sample.id(),sample.mediaType(),bytes.length).resource().getContentAsByteArray());
        }
        byte[] bytes=fixture("sample.tiff");var sample=parse("x.tiff",bytes,"IMAGE_CLASSIFICATION",1048576,20,10000).samples().get(0);
        UUID batch=UUID.fromString(sample.storageKey().split("/")[0]);var storage=new ImportStorage(settings(1048576,20,10000));
        Files.delete(storage.sampleDirectory(batch).resolve(sample.id()+".preview.png"));
        assertThrows(java.io.IOException.class,()->storage.display(batch,sample.id(),sample.mediaType(),bytes.length));
    }
}
