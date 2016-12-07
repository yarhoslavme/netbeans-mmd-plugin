/*
 * Copyright 2016 Igor Maznitsa.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.igormaznitsa.mindmap.plugins.importers;

import java.awt.Color;
import java.awt.Image;
import java.awt.image.RenderedImage;
import java.io.ByteArrayOutputStream;
import com.igormaznitsa.mindmap.plugins.api.AbstractImporter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.commons.io.IOUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import com.igormaznitsa.meta.annotation.MustNotContainNull;
import com.igormaznitsa.meta.common.utils.Assertions;
import com.igormaznitsa.mindmap.model.ExtraFile;
import com.igormaznitsa.mindmap.model.ExtraLink;
import com.igormaznitsa.mindmap.model.ExtraNote;
import com.igormaznitsa.mindmap.model.ExtraTopic;
import com.igormaznitsa.mindmap.model.MMapURI;
import com.igormaznitsa.mindmap.model.MindMap;
import com.igormaznitsa.mindmap.model.Topic;
import com.igormaznitsa.mindmap.swing.panel.DialogProvider;
import com.igormaznitsa.mindmap.swing.panel.MindMapPanel;
import com.igormaznitsa.mindmap.swing.panel.Texts;
import com.igormaznitsa.mindmap.swing.services.IconID;
import com.igormaznitsa.mindmap.swing.services.ImageIconServiceProvider;
import com.igormaznitsa.mindmap.model.logger.Logger;
import com.igormaznitsa.mindmap.model.logger.LoggerFactory;
import com.igormaznitsa.mindmap.plugins.attributes.images.ImageVisualAttributePlugin;
import com.igormaznitsa.mindmap.swing.panel.StandardTopicAttribute;
import com.igormaznitsa.mindmap.swing.panel.utils.Utils;

public class XMind2MindMapImporter extends AbstractImporter {

  private static final Icon ICO = ImageIconServiceProvider.findInstance().getIconForId(IconID.POPUP_IMPORT_XMIND2MM);

  private static final Logger LOGGER = LoggerFactory.getLogger(XMind2MindMapImporter.class);

  private static final class XMindStyle {

    private final Color foreground;
    private final Color background;
    private final Color border;

    private XMindStyle(@Nonnull final Element style) {
      Color back = null;
      Color front = null;
      Color bord = null;

      for (final Element t : getDirectChildren(style, "topic-properties")) {
        final String colorFill = t.getAttribute("svg:fill");
        final String colorText = t.getAttribute("fo:color");
        final String colorBorder = t.getAttribute("border-line-color");
        if (colorFill != null) {
          back = Utils.html2color(colorFill, false);
        }
        if (colorText != null) {
          front = Utils.html2color(colorText, false);
        }
        if (colorBorder != null) {
          bord = Utils.html2color(colorBorder, false);
        }
      }

      this.foreground = front;
      this.background = back;
      this.border = bord;
    }

    private void attachTo(@Nonnull final Topic topic) {
      if (this.background != null) {
        topic.setAttribute(StandardTopicAttribute.ATTR_FILL_COLOR.getText(), Utils.color2html(this.background, false));
      }
      if (this.foreground != null) {
        topic.setAttribute(StandardTopicAttribute.ATTR_TEXT_COLOR.getText(), Utils.color2html(this.foreground, false));
      }
      if (this.border != null) {
        topic.setAttribute(StandardTopicAttribute.ATTR_BORDER_COLOR.getText(), Utils.color2html(this.border, false));
      }
    }

  }

  private static final class XMindStyles {

    private final Map<String, XMindStyle> stylesMap = new HashMap<String, XMindStyle>();

    private XMindStyles(@Nonnull final ZipFile zipFile) {
      try {
        final InputStream stylesXml = getZipInputStream(zipFile, "styles.xml");
        if (stylesXml != null) {
          final Document parsedStyles = extractDocument(stylesXml);

          final Element root = parsedStyles.getDocumentElement();

          if ("xmap-styles".equals(root.getTagName())) {
            for (final Element styles : getDirectChildren(root, "styles")) {
              for (final Element style : getDirectChildren(styles, "style")) {
                final String id = style.getAttribute("id");
                if (!id.isEmpty() && "topic".equals(style.getAttribute("type"))) {
                  this.stylesMap.put(id, new XMindStyle(style));
                }
              }
            }
          }
        }
      }
      catch (Exception ex) {
        LOGGER.error("Can't extract XMIND styles", ex);
      }
    }

    private void setStyle(@Nonnull final String styleId, @Nonnull final Topic topic) {
      final XMindStyle foundStyle = this.stylesMap.get(styleId);
      if (foundStyle != null) {
        foundStyle.attachTo(topic);
      }
    }
  }

  @Nullable
  private static InputStream getZipInputStream(@Nonnull final ZipFile zipFile, @Nonnull final String path) throws IOException {
    final ZipEntry entry = zipFile.getEntry(path);

    InputStream result = null;

    if (entry != null && !entry.isDirectory()) {
      result = zipFile.getInputStream(entry);
    }

    return result;
  }

  @Nullable
  private static byte[] readWholeItemFromZipFile(@Nonnull final ZipFile zipFile, @Nonnull final String path) throws IOException {
    final InputStream in = getZipInputStream(zipFile, path);

    byte[] result = null;

    if (in != null) {
      try {
        result = IOUtils.toByteArray(in);
      }
      finally {
        IOUtils.closeQuietly(in);
      }
    }

    return result;
  }

  private static void throwWrongFormat() {
    throw new IllegalArgumentException("Wrong or unsupported XMind file format");
  }

  @Nonnull
  private static Document extractDocument(@Nonnull final InputStream xmlStream) throws Exception {
    final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setIgnoringComments(true);
    factory.setValidating(false);
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

    final DocumentBuilder builder = factory.newDocumentBuilder();

    final Document document;
    try {
      document = builder.parse(xmlStream);
    }
    finally {
      IOUtils.closeQuietly(xmlStream);
    }

    return document;
  }

  @Override
  @Nullable
  public MindMap doImport(@Nonnull final MindMapPanel panel, @Nonnull final DialogProvider dialogProvider, @Nullable final Topic actionTopic, @Nonnull @MustNotContainNull final Topic[] selectedTopics) throws Exception {
    final File file = this.selectFileForExtension(panel, Texts.getString("MMDImporters.XMind2MindMap.openDialogTitle"), "xmind", "XMind files (.XMIND)", Texts.getString("MMDImporters.ApproveImport"));

    if (file == null) {
      return null;
    }

    final ZipFile zipFile = new ZipFile(file);
    final XMindStyles styles = new XMindStyles(zipFile);

    final InputStream contentStream = getZipInputStream(zipFile, "content.xml");
    if (contentStream == null) {
      throwWrongFormat();
    }
    final Document document = extractDocument(contentStream);

    final Element rootElement = document.getDocumentElement();
    if (!rootElement.getTagName().equals("xmap-content")) {
      throwWrongFormat();
    }

    final List<Element> sheets = getDirectChildren(document.getDocumentElement(), "sheet");

    final MindMap result;

    if (sheets.isEmpty()) {
      result = new MindMap(null, true);
      Assertions.assertNotNull(result.getRoot()).setText("Empty");
    } else {
      result = convertSheet(styles, zipFile, sheets.get(0));
    }

    return result;
  }

  @Nonnull
  private MindMap convertSheet(@Nonnull final XMindStyles styles, @Nonnull final ZipFile file, @Nonnull final Element sheet) throws Exception {
    final MindMap resultedMap = new MindMap(null, true);
    resultedMap.setAttribute(MindMapPanel.ATTR_SHOW_JUMPS, "true");

    final Topic rootTopic = Assertions.assertNotNull(resultedMap.getRoot());
    rootTopic.setText("Empty sheet");

    final Map<String, Topic> topicIdMap = new HashMap<String, Topic>();
    final Map<String, String> linksBetweenTopics = new HashMap<String, String>();

    final List<Element> rootTopics = getDirectChildren(sheet, "topic");
    if (!rootTopics.isEmpty()) {
      convertTopic(file, styles, resultedMap, null, rootTopic, rootTopics.get(0), topicIdMap, linksBetweenTopics);
    }

    for (final Element l : getDirectChildren(sheet, "relationships")) {
      for (final Element r : getDirectChildren(l, "relationship")) {
        final String end1 = r.getAttribute("end1");
        final String end2 = r.getAttribute("end2");
        if (!linksBetweenTopics.containsKey(end1)) {
          final Topic startTopic = topicIdMap.get(end1);
          final Topic endTopic = topicIdMap.get(end2);
          if (startTopic != null && endTopic != null) {
            startTopic.setExtra(ExtraTopic.makeLinkTo(resultedMap, endTopic));
          }
        }
      }
    }

    for (final Map.Entry<String, String> e : linksBetweenTopics.entrySet()) {
      final Topic startTopic = topicIdMap.get(e.getKey());
      final Topic endTopic = topicIdMap.get(e.getValue());
      if (startTopic != null && endTopic != null) {
        startTopic.setExtra(ExtraTopic.makeLinkTo(resultedMap, endTopic));
      }
    }

    return resultedMap;
  }

  @Nonnull
  private static String extractTopicTitle(@Nonnull final Element topic) {
    final List<Element> title = getDirectChildren(topic, "title");
    return title.isEmpty() ? "" : title.get(0).getTextContent();
  }

  @Nonnull
  @MustNotContainNull
  private static List<Element> getChildTopics(@Nonnull final Element topic) {
    List<Element> result = new ArrayList<Element>();

    for (final Element c : getDirectChildren(topic, "children")) {
      for (Element t : getDirectChildren(c, "topics")) {
        result.addAll(getDirectChildren(t, "topic"));
      }
    }

    return result;
  }

  private static void convertTopic(@Nonnull ZipFile zipFile, @Nonnull final XMindStyles styles, @Nonnull final MindMap map, @Nullable final Topic parent, @Nullable Topic pregeneratedOne, @Nonnull final Element topicElement, @Nonnull Map<String, Topic> idTopicMap, @Nonnull final Map<String, String> linksBetweenTopics) throws Exception {
    final Topic topicToProcess;

    if (pregeneratedOne == null) {
      topicToProcess = Assertions.assertNotNull(parent).makeChild("", null);
    } else {
      topicToProcess = pregeneratedOne;
    }

    topicToProcess.setText(extractTopicTitle(topicElement));

    final String theTopicId = topicElement.getAttribute("id");

    idTopicMap.put(theTopicId, topicToProcess);

    final String styleId = topicElement.getAttribute("style-id");
    if (!styleId.isEmpty()) {
      styles.setStyle(styleId, topicToProcess);
    }

    final String attachedImage = extractAttachedImage(zipFile, topicElement);
    if (attachedImage != null && !attachedImage.isEmpty()) {
      topicToProcess.setAttribute(ImageVisualAttributePlugin.ATTR_KEY, attachedImage);
    }

    final String xlink = topicElement.getAttribute("xlink:href");
    if (!xlink.isEmpty()) {
      if (xlink.startsWith("file:")) {
        try {
          topicToProcess.setExtra(new ExtraFile(new MMapURI(new File(xlink.substring(5)).toURI())));
        }
        catch (Exception ex) {
          LOGGER.error("Can't convert file link : " + xlink, ex);
        }
      } else if (xlink.startsWith("xmind:#")) {
        linksBetweenTopics.put(theTopicId, xlink.substring(7));
      } else {
        try {
          topicToProcess.setExtra(new ExtraLink(new MMapURI(URI.create(xlink))));
        }
        catch (Exception ex) {
          LOGGER.error("Can't convert link : " + xlink, ex);
        }
      }
    }

    final String extractedNote = extractNote(topicElement);

    if (!extractedNote.isEmpty()) {
      topicToProcess.setExtra(new ExtraNote(extractedNote));
    }

    for (final Element c : getChildTopics(topicElement)) {
      convertTopic(zipFile, styles, map, topicToProcess, null, c, idTopicMap, linksBetweenTopics);
    }
  }

  @Nullable
  private static String extractAttachedImage(@Nonnull final ZipFile file, @Nonnull final Element topic) {
    String result = null;

    for (final Element e : getDirectChildren(topic, "xhtml:img")) {
      final String link = e.getAttribute("xhtml:src");
      if (!link.isEmpty()) {
        if (link.startsWith("xap:")) {
          InputStream imageStream = null;
          Image loadedImage = null;
          try {
            imageStream = getZipInputStream(file, link.substring(4));
            loadedImage = ImageIO.read(imageStream);
          }
          catch (final Exception ex) {
            LOGGER.error("Can't decode attached image", ex);
          }
          finally {
            IOUtils.closeQuietly(imageStream);
          }

          if (loadedImage != null) {
            try {
              final ByteArrayOutputStream bos = new ByteArrayOutputStream();
              ImageIO.write((RenderedImage) loadedImage, "png", bos); //NOI18N
              bos.close();
              result = Utils.base64encode(bos.toByteArray());
            }
            catch (final Exception ex) {
              LOGGER.error("Can't encode attached image into png", ex);
            }
          }
        }
      }
    }
    return result;
  }

  @Nonnull
  private static String extractNote(@Nonnull final Element topic) {
    final StringBuilder result = new StringBuilder();

    for (final Element note : getDirectChildren(topic, "notes")) {
      final String plain = extractTextContentFrom(note, "plain");
      final String html = extractTextContentFrom(note, "html");

      if (result.length() > 0) {
        result.append('\n');
      }

      if (!plain.isEmpty()) {
        result.append(plain);
      } else if (!html.isEmpty()) {
        result.append(html);
      }
    }

    return result.toString();
  }

  @Nonnull
  private static String extractTextContentFrom(@Nonnull final Element element, @Nonnull final String tag) {
    final StringBuilder result = new StringBuilder();

    for (final Element c : getDirectChildren(element, tag)) {
      final String found = c.getTextContent();
      if (found != null && !found.isEmpty()) {
        result.append(found.replace("\r", ""));
      }
    }

    return result.toString();
  }

  @Nonnull
  @MustNotContainNull
  public static List<Element> getDirectChildren(@Nonnull final Element element, @Nonnull final String name) {
    final NodeList found = element.getElementsByTagName(name);
    final List<Element> resultList = new ArrayList<Element>();

    for (int i = 0; i < found.getLength(); i++) {
      if (found.item(i).getParentNode().equals(element) && found.item(i) instanceof Element) {
        resultList.add((Element) found.item(i));
      }
    }

    return resultList;
  }

  @Override
  @Nullable
  public String getMnemonic() {
    return "xmind";
  }

  @Override
  @Nonnull
  public String getName(@Nonnull final MindMapPanel panel, @Nullable final Topic actionTopic, @Nonnull @MustNotContainNull final Topic[] selectedTopics) {
    return Texts.getString("MMDImporters.XMind2MindMap.Name");
  }

  @Override
  @Nonnull
  public String getReference(@Nonnull final MindMapPanel panel, @Nullable final Topic actionTopic, @Nonnull @MustNotContainNull final Topic[] selectedTopics) {
    return Texts.getString("MMDImporters.XMind2MindMap.Reference");
  }

  @Override
  @Nonnull
  public Icon getIcon(@Nonnull final MindMapPanel panel, @Nullable final Topic actionTopic, @Nonnull @MustNotContainNull final Topic[] selectedTopics) {
    return ICO;
  }

  @Override
  public int getOrder() {
    return 4;
  }
}