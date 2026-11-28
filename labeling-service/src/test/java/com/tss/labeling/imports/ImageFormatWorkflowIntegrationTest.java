package com.tss.labeling.imports;

import com.fasterxml.jackson.databind.*;
import com.tss.labeling.ls.SourceExportWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named="ls.test.python",matches=".+")
class ImageFormatWorkflowIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        ImportIntegrationTest.properties(properties);
        properties.add("labeling.exports.worker-enabled",()->false);
        properties.add("labeling.ls-source.python",()->System.getProperty("ls.test.python"));
    }
    @Autowired ImportStorage storage;@Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired ImportWorker imports;@Autowired com.tss.labeling.ls.SourceExportService exportService;
    static final String API="/api/labeling/v1";
    JsonNode postApi(String path,Object body,int expected)throws Exception {
        var response=mvc.perform(post(API+path).header("Authorization","Bearer owner").header("Idempotency-Key",UUID.randomUUID().toString())
                .contentType("application/json").content(json.writeValueAsBytes(body))).andReturn().getResponse();
        assertEquals(expected,response.getStatus(),response.getContentAsString());return json.readTree(response.getContentAsString()).path("data");
    }
    JsonNode getApi(String path)throws Exception {
        return json.readTree(mvc.perform(get(API+path).header("Authorization","Bearer owner")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
    }
    String upload(String project,Map<String,byte[]> files)throws Exception {
        byte[] bytes=ImageTestFiles.zip(files);
        String batch=postApi("/projects/"+project+"/imports",Map.of("filename","images.zip","sizeBytes",bytes.length,"sha256",ImageTestFiles.sha(bytes)),201).path("batchId").asText();
        for(int offset=0;offset<bytes.length;offset+=256) {
            byte[] part=Arrays.copyOfRange(bytes,offset,Math.min(offset+256,bytes.length));
            mvc.perform(put(API+"/imports/"+batch+"/chunks").queryParam("offset",Integer.toString(offset)).header("Authorization","Bearer owner")
                    .header("X-Chunk-SHA256",ImageTestFiles.sha(part)).contentType("application/octet-stream").content(part)).andExpect(status().isOk());
        }
        mvc.perform(post(API+"/imports/"+batch+"/complete").header("Authorization","Bearer owner").header("Idempotency-Key",UUID.randomUUID().toString())).andExpect(status().isAccepted());
        imports.runOnce();assertEquals("READY",getApi("/imports/"+batch).path("status").asText());return batch;
    }
    @Test void classificationPagesExposeAuthorizedPngWithoutChangingOriginalMetadata()throws Exception {
        String project=postApi("/projects",Map.of("name","TIFF分类","taskType","IMAGE_CLASSIFICATION"),201).path("projectId").asText();
        byte[] original=ImageFormatImportTest.fixture("two-pages.tiff");String batch=upload(project,Map.of("中文/扫描.TIFF",original));
        JsonNode items=getApi("/imports/"+batch+"/samples").path("items");assertEquals(2,items.size());
        for(JsonNode sample:items) {
            assertEquals("image/tiff",sample.path("mediaType").asText());assertEquals(ImageTestFiles.sha(original),sample.path("contentSha256").asText());
            String url=sample.path("fileUrl").asText();
            var response=mvc.perform(get(url).header("Authorization","Bearer owner")).andExpect(status().isOk()).andExpect(content().contentType("image/png")).andReturn().getResponse();
            var png=ImageIO.read(new ByteArrayInputStream(response.getContentAsByteArray()));
            assertEquals(sample.at("/metadata/width").asInt(),png.getWidth());assertEquals(sample.at("/metadata/height").asInt(),png.getHeight());
            mvc.perform(get(url).header("Authorization","Bearer other")).andExpect(status().isNotFound());
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
        }
    }
    @ParameterizedTest
    @CsvSource({"scan.TIFF,two-pages.tiff,#page-2,5,4", "photo.webp,sample-lossless.webp,'',8,6", "photo.BMP,sample.bmp,'',8,6"})
    void nativeFormatsSupportPageCoordinatesReviewAndRealExports(String filename,String fixture,String page,int width,int height)throws Exception {
        String project=postApi("/projects",Map.of("name","新格式检测","taskType","COMPUTER_VISION"),201).path("projectId").asText();
        byte[] original=ImageFormatImportTest.fixture(fixture);
        String reference="images/"+filename+page;
        String batch=upload(project,Map.of("images/"+filename,original,"tasks.json",json.writeValueAsBytes(Map.of("data",Map.of("image",reference)))));
        String sample=getApi("/projects/"+project+"/input-samples").at("/items/0/sampleId").asText();
        String xml="<View><Image name=\"image\" value=\"$image\"/><RectangleLabels name=\"boxes\" toName=\"image\"><Label value=\"obj\"/></RectangleLabels></View>";
        String spec=postApi("/projects/"+project+"/spec-versions",Map.of("taskType","COMPUTER_VISION","schemaVersion","label-studio-v1",
                "labels",List.of(),"editorConfig",Map.of("labelConfig",xml,"templateId","custom")),201).path("specVersionId").asText();
        JsonNode owner=getApi("/projects/"+project+"/samples/"+sample+"/task?specVersionId="+spec);
        mvc.perform(get(owner.at("/resources/0/url").asText()).header("Authorization","Bearer owner")).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        JsonNode plan=postApi("/projects/"+project+"/package-plans",Map.of("specVersionId",spec,"samples",List.of(Map.of("sampleId",sample))),201);
        JsonNode published=postApi("/package-plans/"+plan.path("planId").asText()+"/publish",Map.of("selectionHash",plan.path("selectionHash").asText()),200);
        String assignment=postApi("/packages/"+published.at("/packages/0/packageId").asText()+"/claims",Map.of(),201).path("assignmentId").asText();
        JsonNode context=getApi("/assignments/"+assignment+"/samples/"+sample+"/task");String url=context.at("/resources/0/url").asText();
        byte[] png=mvc.perform(get(url).header("Authorization","Bearer owner")).andExpect(status().isOk()).andExpect(content().contentType("image/png")).andReturn().getResponse().getContentAsByteArray();
        var image=ImageIO.read(new ByteArrayInputStream(png));assertEquals(width,image.getWidth());assertEquals(height,image.getHeight());
        mvc.perform(get(url).header("Authorization","Bearer other")).andExpect(status().isNotFound());
        var region=Map.of("id","box","from_name","boxes","to_name","image","type","rectanglelabels","original_width",width,"original_height",height,
                "value",Map.of("x",20,"y",25,"width",40,"height",50,"rotation",0,"rectanglelabels",List.of("obj")));
        mvc.perform(put(API+"/assignments/"+assignment+"/drafts/"+sample).header("Authorization","Bearer owner").contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("baseRevision",0,"explicitDone",true,"resultFormat","label-studio-v1","result",Map.of("result",List.of(region)))))).andExpect(status().isOk());
        String submission=postApi("/assignments/"+assignment+"/submissions",Map.of("items",List.of(Map.of("sampleId",sample,"revision",1))),201).path("submissionId").asText();
        postApi("/submissions/"+submission+"/review/decide",Map.of("action","ACCEPT"),200);
        String snapshot=postApi("/projects/"+project+"/final-snapshots",Map.of("name","已审核","specVersionId",spec,"sampleIds",List.of(sample)),201).path("finalSnapshotId").asText();
        for(String format:List.of("JSON","COCO","YOLO","VOC")) {
            String export=postApi("/final-snapshots/"+snapshot+"/exports",Map.of("format",format),202).path("exportId").asText();exportService.generate(UUID.fromString(export));
            assertEquals("READY",getApi("/source-exports/"+export).path("status").asText(),format);
            byte[] archive=mvc.perform(get(API+"/source-exports/"+export+"/file").header("Authorization","Bearer owner")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            Map<String,byte[]> files=new HashMap<>();
            try(var zip=new ZipInputStream(new ByteArrayInputStream(archive),StandardCharsets.UTF_8)) {
                for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry())files.put(entry.getName(),zip.readAllBytes());
            }
            assertTrue(files.entrySet().stream().anyMatch(item->item.getKey().startsWith("originals/")&&Arrays.equals(original,item.getValue())),files.keySet().toString());
            assertTrue(files.keySet().stream().anyMatch(name->name.endsWith(".png")));
            if(format.equals("JSON")) {
                var meta=json.readTree(files.get("result.json")).at("/0/meta/platformImageSources/0");
                assertEquals(reference.replace(page,""),meta.path("originalSourcePath").asText());
                assertEquals(page.isEmpty()?0:1,meta.path("pageIndex").asInt());assertEquals(ImageTestFiles.sha(original),meta.path("sha256").asText());
            }
            if(format.equals("VOC")) {
                byte[] annotationXml=files.entrySet().stream().filter(item->item.getKey().startsWith("Annotations/")&&item.getKey().endsWith(".xml")).findFirst().orElseThrow().getValue();
                var doc=javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(annotationXml));
                assertEquals(Integer.toString(width),doc.getElementsByTagName("width").item(0).getTextContent());
                assertEquals(Integer.toString(height),doc.getElementsByTagName("height").item(0).getTextContent());
                assertEquals("obj",doc.getElementsByTagName("name").item(0).getTextContent());
                assertTrue(files.containsKey("annotation-index.json"));
            }
            if(format.equals("COCO")) {
                var coco=json.readTree(files.get("result.json"));assertEquals(width,coco.at("/images/0/width").asInt());assertEquals(height,coco.at("/images/0/height").asInt());
                assertEquals(width*0.4,coco.at("/annotations/0/bbox/2").asDouble(),0.001);
            }
        }
        if(filename.endsWith("TIFF")) {
            UUID resource=UUID.fromString(owner.at("/resources/0/resourceId").asText());
            var preview=storage.sampleDirectory(UUID.fromString(batch)).resolve(resource+".preview.png");
            byte[] clean=java.nio.file.Files.readAllBytes(preview),changed=clean.clone();changed[changed.length-1]^=1;
            String tampered=postApi("/final-snapshots/"+snapshot+"/exports",Map.of("format","JSON"),202).path("exportId").asText();
            try {
                java.nio.file.Files.write(preview,changed);
                var failure=assertThrows(com.tss.labeling.common.BusinessException.class,()->exportService.generate(UUID.fromString(tampered)));
                assertEquals("INPUT_HASH_MISMATCH",failure.code());
            } finally {java.nio.file.Files.write(preview,clean);exportService.fail(UUID.fromString(tampered),"INPUT_HASH_MISMATCH");}
        }
    }

    @Test void reviewedBrushOnTiffPageExportsBinaryMaskWithOriginalAndPageAssociation()throws Exception {
        String project=postApi("/projects",Map.of("name","TIFF分割","taskType","COMPUTER_VISION"),201).path("projectId").asText();
        byte[] original=ImageFormatImportTest.fixture("two-pages.tiff");
        upload(project,Map.of("scan.tiff",original,"tasks.json",json.writeValueAsBytes(Map.of("data",Map.of("image","scan.tiff#page-2")))));
        String sample=getApi("/projects/"+project+"/input-samples").at("/items/0/sampleId").asText();
        String xml="<View><Image name=\"image\" value=\"$image\"/><BrushLabels name=\"masks\" toName=\"image\"><Label value=\"obj\"/></BrushLabels></View>";
        String spec=postApi("/projects/"+project+"/spec-versions",Map.of("taskType","COMPUTER_VISION","schemaVersion","label-studio-v1",
                "labels",List.of(),"editorConfig",Map.of("labelConfig",xml,"templateId","custom")),201).path("specVersionId").asText();
        JsonNode plan=postApi("/projects/"+project+"/package-plans",Map.of("specVersionId",spec,"samples",List.of(Map.of("sampleId",sample))),201);
        JsonNode published=postApi("/package-plans/"+plan.path("planId").asText()+"/publish",Map.of("selectionHash",plan.path("selectionHash").asText()),200);
        String assignment=postApi("/packages/"+published.at("/packages/0/packageId").asText()+"/claims",Map.of(),201).path("assignmentId").asText();
        JsonNode region=json.createObjectNode().put("id","mask").put("from_name","masks").put("to_name","image").put("type","brushlabels")
                .put("original_width",5).put("original_height",4);
        var value=((com.fasterxml.jackson.databind.node.ObjectNode)region).putObject("value");
        value.put("format","rle");value.set("rle",json.readTree(ImageFormatImportTest.fixture("brush-rle.json")));value.putArray("brushlabels").add("obj");
        mvc.perform(put(API+"/assignments/"+assignment+"/drafts/"+sample).header("Authorization","Bearer owner").contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("baseRevision",0,"explicitDone",true,"resultFormat","label-studio-v1","result",Map.of("result",List.of(region)))))).andExpect(status().isOk());
        String submission=postApi("/assignments/"+assignment+"/submissions",Map.of("items",List.of(Map.of("sampleId",sample,"revision",1))),201).path("submissionId").asText();
        postApi("/submissions/"+submission+"/review/decide",Map.of("action","ACCEPT"),200);
        String snapshot=postApi("/projects/"+project+"/final-snapshots",Map.of("name","分割成果","specVersionId",spec,"sampleIds",List.of(sample)),201).path("finalSnapshotId").asText();
        String export=postApi("/final-snapshots/"+snapshot+"/exports",Map.of("format","BRUSH_TO_PNG"),202).path("exportId").asText();
        exportService.generate(UUID.fromString(export));assertEquals("READY",getApi("/source-exports/"+export).path("status").asText());
        byte[] archive=mvc.perform(get(API+"/source-exports/"+export+"/file").header("Authorization","Bearer owner")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        Map<String,byte[]> files=new HashMap<>();
        try(var zip=new ZipInputStream(new ByteArrayInputStream(archive),StandardCharsets.UTF_8)) {
            for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry())files.put(entry.getName(),zip.readAllBytes());
        }
        JsonNode index=json.readTree(files.get("annotation-index.json"));
        String mask=index.at("/items/0/mask").asText();var png=ImageIO.read(new ByteArrayInputStream(files.get(mask)));
        assertEquals(5,png.getWidth());assertEquals(4,png.getHeight());
        assertEquals(255,png.getRaster().getSample(1,1,0));assertEquals(0,png.getRaster().getSample(0,0,0));
        assertEquals(1,index.at("/items/0/source/platformImageSources/0/pageIndex").asInt());
        assertTrue(files.entrySet().stream().anyMatch(item->item.getKey().startsWith("originals/")&&Arrays.equals(original,item.getValue())));
        mvc.perform(get(API+"/source-exports/"+export+"/file").header("Authorization","Bearer other")).andExpect(status().isNotFound());
    }
}
