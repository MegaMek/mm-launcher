/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek Launcher.
 *
 * MegaMek Launcher is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek Launcher is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */

package org.megamek.launcher.gui;

import org.megamek.launcher.release.JavaReleaseTransport;
import org.megamek.launcher.release.ReleaseTransport;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class LauncherNewsFeed {
    static final URI FEED = URI.create("https://megamek.org/feed.xml");
    static final URI ARCHIVE = URI.create("https://megamek.org/archives.html");
    private static final String ATOM = "http://www.w3.org/2005/Atom";
    private static final int MAX_BYTES = 1_048_576;
    private final ReleaseTransport transport;

    LauncherNewsFeed() {
        this(new JavaReleaseTransport());
    }

    LauncherNewsFeed(ReleaseTransport transport) {
        this.transport = transport;
    }

    List<Article> latest() throws IOException, InterruptedException {
        try (ReleaseTransport.Response response =
                     transport.get(FEED, "application/atom+xml")) {
            if (response.status() != 200) {
                throw new IOException("MegaMek news returned HTTP " + response.status());
            }
            byte[] body = response.body().readNBytes(MAX_BYTES + 1);
            if (body.length > MAX_BYTES) {
                throw new IOException("MegaMek news feed exceeds the size limit");
            }
            return parse(body);
        }
    }

    static List<Article> parse(byte[] body) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document document = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(body));
            Element root = document.getDocumentElement();
            if (!ATOM.equals(root.getNamespaceURI()) || !"feed".equals(root.getLocalName())) {
                throw new IOException("MegaMek news is not an Atom feed");
            }
            NodeList entries = root.getElementsByTagNameNS(ATOM, "entry");
            List<Article> articles = new ArrayList<>();
            for (int i = 0; i < entries.getLength(); i++) {
                Element entry = (Element) entries.item(i);
                String title = childText(entry, "title");
                String published = childText(entry, "published");
                URI link = articleLink(entry);
                if (title == null || title.isBlank() || published == null || link == null) {
                    throw new IOException("MegaMek news entry is incomplete");
                }
                LocalDate date;
                try {
                    date = OffsetDateTime.parse(published).toLocalDate();
                } catch (DateTimeParseException error) {
                    throw new IOException("MegaMek news date is invalid", error);
                }
                articles.add(new Article(title.strip(), date, link));
            }
            if (articles.isEmpty()) throw new IOException("MegaMek news feed is empty");
            articles.sort(Comparator.comparing(Article::published).reversed());
            return List.copyOf(articles.subList(0, Math.min(3, articles.size())));
        } catch (ParserConfigurationException | SAXException | IllegalArgumentException error) {
            throw new IOException("MegaMek news feed could not be read safely", error);
        }
    }

    private static String childText(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && ATOM.equals(element.getNamespaceURI())
                    && name.equals(element.getLocalName())) {
                return element.getTextContent();
            }
        }
        return null;
    }

    private static URI articleLink(Element entry) throws IOException {
        for (Node node = entry.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element link) || !ATOM.equals(link.getNamespaceURI())
                    || !"link".equals(link.getLocalName())
                    || !"alternate".equals(link.getAttribute("rel"))) {
                continue;
            }
            try {
                URI uri = new URI(link.getAttribute("href"));
                if (!"https".equals(uri.getScheme())
                        || !"megamek.org".equals(uri.getHost())
                        || uri.getUserInfo() != null || uri.getPort() != -1
                        || uri.getRawPath() == null || uri.getRawPath().isBlank()) {
                    throw new IOException("MegaMek news article link is not on the official site");
                }
                return uri;
            } catch (URISyntaxException error) {
                throw new IOException("MegaMek news article link is invalid", error);
            }
        }
        return null;
    }

    record Article(String title, LocalDate published, URI link) {
    }
}
