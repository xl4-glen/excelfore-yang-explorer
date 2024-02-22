/**
 * Copyright (c) 2018 Cisco Systems
 *
 * Author: Steven Barth <stbarth@cisco.com>
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.xl4.netconf.anx;

import com.xl4.netconf.anc.*;
import com.vaadin.annotations.PreserveOnRefresh;
import com.vaadin.data.TreeData;
import com.vaadin.data.provider.TreeDataProvider;
import com.vaadin.event.ShortcutAction.KeyCode;
import com.vaadin.icons.VaadinIcons;
import com.vaadin.navigator.View;
import com.vaadin.navigator.ViewBeforeLeaveEvent;
import com.vaadin.navigator.ViewChangeListener.ViewChangeEvent;
import com.vaadin.server.FileResource;
import com.vaadin.server.Page;
import com.vaadin.server.VaadinService;
import com.vaadin.shared.ui.ContentMode;
import com.vaadin.ui.*;
import com.vaadin.ui.themes.ValoTheme;
import org.opendaylight.yangtools.yang.model.api.Module;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Main view showing schema and data tree
 */
@SuppressWarnings("serial")
@PreserveOnRefresh
public final class SalesView extends VerticalLayout implements View {

  private Tree<WrappedYangNode> schemaTree;
  private Tree<XMLElement> dataTree;
  private XMLElement dataElements = new XMLElement(null, "data");
  private String dataQuery;
  private Netconf.Datastore dataSource;
  private Panel treePanel = new Panel();

  private static ArrayList<ParentKey> parentKeyList = new ArrayList<>();

  private String command = "get";

  String host;
  String username;
  String password;
  NetconfClient client;
  NetconfYangParser parser;
  WrappedYangNode selectedNode;
  XMLElement selectedData;

  private MessageCallback callback;

  // Setting and Configuration
  CheckBox dataCommandCheckBox = new CheckBox("Get 'RW' Data Only");
  CheckBox multiCheckBox = new CheckBox("Enable Multiple Selection");

  public SalesView(String host, String username, String password, NetconfClient client, NetconfYangParser parser) {
    this.host = host;
    this.username = username;
    this.password = password;
    this.client = client;
    this.parser = parser;

    setSizeFull();
    setMargin(false);

    // Build topbar
    String basepath = VaadinService.getCurrent().getBaseDirectory().getAbsolutePath();
    FileResource resource = new FileResource(new File(basepath + "/WEB-INF/images/excelfore.png"));
    Image image = new Image(null, resource);
    image.addStyleName("xl4-logo");

    Label welcome = new Label("Excelfore Yang Explorer");
    welcome.addStyleName("topbartitle");
    welcome.setSizeUndefined();

    HorizontalLayout labels = new HorizontalLayout(image, welcome);
    labels.setSpacing(false);

    Label padding = new Label();

    Label connected = new Label(String.format("Device %s (%d YANG models)",
        host, parser.getSchemaContext().getModules().size()));

    Button refreshButton = new Button(VaadinIcons.REFRESH);
    refreshButton.setPrimaryStyleName(ValoTheme.BUTTON_BORDERLESS);

    Button configButton = new Button(VaadinIcons.COG);
    configButton.setDescription("Settings");
    configButton.setPrimaryStyleName(ValoTheme.BUTTON_BORDERLESS);
    configButton.addClickListener(x -> showConfig());

    Button disconnectButton = new Button(VaadinIcons.SIGN_OUT);
    disconnectButton.setPrimaryStyleName(ValoTheme.BUTTON_BORDERLESS);
    disconnectButton.addClickListener(x -> {
      try {
        client.close();
      } catch (Exception e) {
      }
      Page.getCurrent().reload();
    });

    HorizontalLayout topbar = new HorizontalLayout();
    topbar.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);
    topbar.addComponents(labels, padding, connected, refreshButton, configButton, disconnectButton);
    topbar.setExpandRatio(padding, 1.0f);
    topbar.setWidth("100%");
    topbar.setMargin(true);
    topbar.addStyleName("topbar");
    addComponent(topbar);

    // Define main layout: sidebar + content
    HorizontalLayout mainLayout = new HorizontalLayout();
    mainLayout.setSizeFull();
    mainLayout.setMargin(false);
    addComponent(mainLayout);
    setExpandRatio(mainLayout, 1.0f);

    HorizontalLayout buttonLayout = new HorizontalLayout();
    try {
      buttonLayout.addComponent(new NetconfTools(this).createComponent());
    } catch (NetconfException e) {
      Notification.show(e.getMessage(), Notification.Type.ERROR_MESSAGE);
    }

    // Define content (right-hand side)
    VerticalLayout contentLayout = new VerticalLayout();
    contentLayout.setSizeFull();

    Panel contentPanel = new Panel(contentLayout);
    contentPanel.setSizeFull();
    mainLayout.addComponent(contentPanel);

    // Button and filter layout
    HorizontalLayout secondLayout = new HorizontalLayout();
    HorizontalLayout schemaFilterLayout = new HorizontalLayout();

    TextField schemaModuleFilter = new TextField();
    schemaModuleFilter.setPlaceholder("Search models");
    schemaModuleFilter.setWidth("150px");
    schemaModuleFilter.focus();
    schemaFilterLayout.addComponent(schemaModuleFilter);

    TextField schemaNodeFilter = new TextField();
    schemaNodeFilter.setPlaceholder("Search nodes");
    schemaNodeFilter.setWidth("150px");
    schemaFilterLayout.addComponent(schemaNodeFilter);

    Button schemaFilterApply = new Button("Apply", VaadinIcons.SEARCH);
    schemaFilterApply.addStyleName(ValoTheme.BUTTON_FRIENDLY);
    schemaFilterApply.setClickShortcut(KeyCode.ENTER);
    schemaFilterLayout.addComponent(schemaFilterApply);

    Button schemaFilterClear = new Button("Clear", VaadinIcons.ERASER);
    schemaFilterClear.addStyleName(ValoTheme.BUTTON_FRIENDLY);
    schemaFilterLayout.addComponent(schemaFilterClear);

    HorizontalLayout dataFilterLayout = new HorizontalLayout();
    dataFilterLayout.setDefaultComponentAlignment(Alignment.BOTTOM_CENTER);
    dataFilterLayout.setVisible(false);

    TextField dataNodeFilter = new TextField();
    dataNodeFilter.setPlaceholder("Search nodes");
    dataNodeFilter.setWidth("150px");
    dataNodeFilter.focus();
    dataFilterLayout.addComponent(dataNodeFilter);

    TextField dataValueFilter = new TextField();
    dataValueFilter.setPlaceholder("Search values");
    dataValueFilter.setWidth("150px");
    dataFilterLayout.addComponent(dataValueFilter);

    Button dataFilterApply = new Button("Apply", VaadinIcons.SEARCH);
    dataFilterApply.addStyleName(ValoTheme.BUTTON_FRIENDLY);
    dataFilterApply.setClickShortcut(KeyCode.ENTER);
    dataFilterLayout.addComponent(dataFilterApply);
    dataFilterLayout.setComponentAlignment(dataFilterApply, Alignment.BOTTOM_CENTER);

    Button dataFilterClear = new Button("Clear", VaadinIcons.ERASER);
    dataFilterClear.addStyleName(ValoTheme.BUTTON_FRIENDLY);
    dataFilterLayout.addComponent(dataFilterClear);
    dataFilterLayout.setComponentAlignment(dataFilterClear, Alignment.BOTTOM_CENTER);

    // Label layout
    HorizontalLayout labelLayout = new HorizontalLayout();
    labelLayout.setWidth("100%");

    Label modeLabel = new Label();
    modeLabel.setContentMode(ContentMode.HTML);
    modeLabel.setValue(VaadinIcons.FILE_TREE.getHtml() + " Schema View");
    modeLabel.addStyleName(ValoTheme.LABEL_H3);
    modeLabel.addStyleName(ValoTheme.LABEL_BOLD);

    Consumer<Netconf.Datastore> showSourceAction = x -> {
      if (dataSource != x)
        dataQuery = null;
      dataSource = x;
      schemaFilterLayout.setVisible(false);
      dataFilterLayout.setVisible(true);
      dataFilterClear.click();
      modeLabel.setValue(VaadinIcons.DATABASE.getHtml() + " Data View");
    };

    HorizontalLayout switchToDataViewLayout = new HorizontalLayout();
    switchToDataViewLayout.setMargin(false);
    switchToDataViewLayout.setSpacing(false);

    Button showRunningData = new Button("Switch to Data View", VaadinIcons.EXCHANGE);
    showRunningData.addStyleName(ValoTheme.BUTTON_PRIMARY);

    // CheckBox dataCommandCheckBox = new CheckBox("get 'rw' data only");
    // dataCommandCheckBox.addStyleName(ValoTheme.CHECKBOX_SMALL);
    // dataCommandCheckBox.addStyleName("data-switch-checkbox");
    // dataCommandCheckBox.addValueChangeListener(event -> {
    //   command = event.getValue().booleanValue() ? "get-config" : "get";
    // });

    // switchToDataViewLayout.addComponent(dataCommandCheckBox);
    // switchToDataViewLayout.setComponentAlignment(dataCommandCheckBox, Alignment.MIDDLE_RIGHT);
    switchToDataViewLayout.addComponent(showRunningData);
    switchToDataViewLayout.setComponentAlignment(showRunningData, Alignment.MIDDLE_RIGHT);

    Button showSchemas = new Button("Switch to Schema View", VaadinIcons.EXCHANGE);
    showSchemas.addStyleName(ValoTheme.BUTTON_PRIMARY);
    showSchemas.setVisible(false);
    labelLayout.addComponent(modeLabel);
    labelLayout.addComponent(switchToDataViewLayout);
    labelLayout.addComponent(showSchemas);
    labelLayout.setComponentAlignment(modeLabel, Alignment.MIDDLE_LEFT);
    labelLayout.setComponentAlignment(switchToDataViewLayout, Alignment.MIDDLE_RIGHT);
    labelLayout.setComponentAlignment(showSchemas, Alignment.MIDDLE_RIGHT);

    showRunningData.addClickListener(x -> {
      showSourceAction.accept(Netconf.Datastore.RUNNING);
      switchToDataViewLayout.setVisible(false);
      showSchemas.setVisible(true);
    });

    secondLayout.addComponent(schemaFilterLayout);
    secondLayout.addComponent(dataFilterLayout);
    secondLayout.addComponent(buttonLayout);
    secondLayout.setWidthFull();
    secondLayout.setComponentAlignment(schemaFilterLayout, Alignment.MIDDLE_LEFT);
    secondLayout.setComponentAlignment(dataFilterLayout, Alignment.MIDDLE_LEFT);
    secondLayout.setComponentAlignment(buttonLayout, Alignment.MIDDLE_RIGHT);

    contentLayout.addComponent(labelLayout);
    contentLayout.addComponent(secondLayout);

    // Data or schema tree definition
    treePanel.setHeight("100%");
    contentLayout.addComponent(treePanel);
    contentLayout.setExpandRatio(treePanel, 1.0f);

    schemaFilterApply.addClickListener(e -> treePanel.setContent(
        showSchemaTree(schemaModuleFilter.getValue(), schemaNodeFilter.getValue())));

    dataFilterApply.addClickListener(e -> treePanel.setContent(
        showDataTree(dataNodeFilter.getValue(), dataValueFilter.getValue())));

    schemaFilterClear.addClickListener(e -> {
      schemaModuleFilter.clear();
      schemaModuleFilter.focus();
      schemaNodeFilter.clear();
      schemaFilterApply.click();
    });

    dataFilterClear.addClickListener(e -> {
      dataNodeFilter.clear();
      dataNodeFilter.focus();
      dataValueFilter.clear();
      dataFilterApply.click();
    });

    showSchemas.addClickListener(x -> {
      schemaFilterLayout.setVisible(true);
      dataFilterLayout.setVisible(false);
      treePanel.setContent(schemaTree);
      selectedData = null;
      modeLabel.setValue(VaadinIcons.FILE_TREE.getHtml() + " Schema View");
      switchToDataViewLayout.setVisible(true);
      showSchemas.setVisible(false);
    });

    treePanel.setContent(showSchemaTree("", ""));

    multiCheckBox.addValueChangeListener(event -> {
      schemaTree.setSelectionMode(event.getValue() ? Grid.SelectionMode.MULTI : Grid.SelectionMode.SINGLE);
    });

    refreshButton.addClickListener(x -> {
      refreschSchemas();
      schemaFilterClear.click();
      dataFilterClear.click();
      treePanel.setContent(showSchemaTree("", ""));
      schemaTree.setSelectionMode(
          multiCheckBox.getValue().booleanValue() ? Grid.SelectionMode.MULTI : Grid.SelectionMode.SINGLE);
    });
  }

  // Show the schema tree based on the current collected YANG models
  private Tree<WrappedYangNode> showSchemaTree(String moduleFilter, String fieldFilter) {
    List<String> moduleQuery = Arrays.asList(moduleFilter.toLowerCase().split(" "));
    List<String> fieldQuery = Arrays.asList(fieldFilter.toLowerCase().split(" "));

    schemaTree = new Tree<>();
    schemaTree.setSelectionMode(Grid.SelectionMode.SINGLE);
    schemaTree.setItemCaptionGenerator(WrappedYangNode::getCaption);
    schemaTree.setItemDescriptionGenerator(this::getNodeTooltipString, ContentMode.HTML);
    schemaTree.setItemIconGenerator(x -> x.isKey() ? VaadinIcons.KEY : null);
    schemaTree.addItemClickListener(x -> showYangNode(x.getItem()));

    // Iterate YANG models, apply filters and add matching schema nodes
    TreeData<WrappedYangNode> data = new TreeData<>();
    for (Module module : parser.getSchemaContext().getModules()) {
      String name = module.getName().toLowerCase();
      String description = module.getDescription().orElse("").toLowerCase();
      if (moduleQuery.stream().filter(name::contains).count() == moduleQuery.size() ||
          moduleQuery.stream().filter(description::contains).count() == moduleQuery.size())
        new WrappedYangNode(module).addToTree(data, fieldQuery);
    }

    // Define data provide and ordering of YANG nodes and render on tree widget
    TreeDataProvider<WrappedYangNode> dataProvider = new TreeDataProvider<>(data);
    dataProvider.setSortComparator(Comparator.comparing(WrappedYangNode::isKey)
        .thenComparing(WrappedYangNode::getName)::compare);
    schemaTree.setDataProvider(dataProvider);

    // Expand the first 100 direct filter matches automatically
    int remain = 100;
    parentKeyList.clear();
    for (WrappedYangNode module : data.getRootItems()) {
      traverseAllChildNodes(module);
      remain = module.applyExpand(schemaTree, remain);
    }

    if (remain <= 0)
      Notification.show("Too many search results! They are all shown, but only 100 have been auto-expanded.",
          Notification.Type.TRAY_NOTIFICATION);

    return schemaTree;
  }

  // Show a tree of live data from the device
  private Tree<XMLElement> showDataTree(String moduleFilter, String fieldFilter) {
    List<String> moduleQuery = Arrays.asList(moduleFilter.toLowerCase().split(" "));
    List<String> fieldQuery = Arrays.asList(fieldFilter.toLowerCase().split(" "));

    dataTree = new Tree<>();

    // Show name of the node/leaf and value (if available)
    dataTree.setItemCaptionGenerator(x -> {
      NodeList childNodes = x.getChildNodes();

      Optional<Element> matchingNode = IntStream.range(0, childNodes.getLength())
          .mapToObj(childNodes::item)
          .filter(node -> node.getNodeType() == Node.ELEMENT_NODE)
          .map(node -> (Element) node)
          .filter(element -> containsCombination(element.getParentNode().getNodeName(), element.getNodeName()))
          .findFirst();

      String path = "";
      String namespace = "";
      for (XMLElement element = x; element != null; element = element.getParent()) {
        path = "/" + element.getName() + path;
        namespace = element.getNamespace();
        if (element.getAttribute("root").equals("1"))
          break;
      }
      path = path.substring(1);
      selectedData = x;

      boolean isTimeInterval = false;
      // Iterate YANG schemas to find the schema node associated with the data
      for (Module module : parser.getSchemaContext().getModules()) {
        if (module.getNamespace().toString().equals(namespace)) {
          Optional<WrappedYangNode> optNode = WrappedYangNode.byPath(new WrappedYangNode(module), path);
          WrappedYangNode node = optNode.isPresent() ? optNode.get() : null;
          if (node != null && node.getDataType().equals("time-interval")) {
            isTimeInterval = true;
          }
        }
      }

      if (matchingNode.isPresent()) {
        Element element = (Element) matchingNode.get();
        return x.getName() + " (" + element.getNodeName() + " = " + element.getTextContent() + ")";
      } else {
        String value = x.getText();
        if (isTimeInterval) {
          value = isValidTimeInterval(value) ? Long.toString(Long.parseLong(value) >> 16) : "Invalid";
        }

        return x.getName().concat(x.stream().count() > 0 ? "" : (" = " + value));
      }
    });

    dataTree.addItemClickListener(x -> {
      // Build (X)Path of selected element and find namespace

      String path = "";
      String namespace = "";
      for (XMLElement element = x.getItem(); element != null; element = element.getParent()) {
        path = "/" + element.getName() + path;
        namespace = element.getNamespace();

        if (element.getAttribute("root").equals("1"))
          break;
      }
      path = path.substring(1);
      selectedData = x.getItem();

      // Iterate YANG schemas to find the schema node associated with the data
      for (Module module : parser.getSchemaContext().getModules())
        if (module.getNamespace().toString().equals(namespace))
          WrappedYangNode.byPath(new WrappedYangNode(module), path).ifPresent(this::showYangNode);
    });

    dataTree.setItemDescriptionGenerator(x -> {
      String path = "";
      String namespace = "";
      for (XMLElement element = x; element != null; element = element.getParent()) {
        path = "/" + element.getName() + path;
        namespace = element.getNamespace();
        if (element.getAttribute("root").equals("1"))
          break;
      }
      path = path.substring(1);
      selectedData = x;

      // Iterate YANG schemas to find the schema node associated with the data
      for (Module module : parser.getSchemaContext().getModules()) {
        if (module.getNamespace().toString().equals(namespace)) {
          Optional<WrappedYangNode> node = WrappedYangNode.byPath(new WrappedYangNode(module), path);
          if (node.isPresent()) {
            return getNodeTooltipString(node.get());
          }
        }
      }
      return null;
    }, ContentMode.HTML);

    // Get selected schema elements and build a NETCONF combined subtree-filter to
    // retrieve all of them with a single get-call
    LinkedList<XMLElement> subtreeFilter = new LinkedList<>();
    Set<WrappedYangNode> items = schemaTree.getSelectedItems();

    for (WrappedYangNode item : items) {
      boolean unique = true;

      for (WrappedYangNode c = item.getParent(); unique && c != null; c = c.getParent())
        if (items.contains(c))
          unique = false;

      // Only add new subtree filter if we don't have it or any parent element
      // selected already
      if (unique) {
        item.createNetconfTemplate().map(Stream::of).orElse(item.getChildren()
            .map(WrappedYangNode::createNetconfTemplate).filter(Optional::isPresent).map(Optional::get))
            .forEach(subtreeFilter::add);
      }
    }

    // Cache retrieved config data if selected fields are the same and just filters
    // change
    String newQuery = subtreeFilter.stream().map(XMLElement::toXML).collect(Collectors.joining());
    if (!newQuery.equals(dataQuery)) {
      try (NetconfSession session = client.createSession()) {
        // Query peer using NETCONF to retrieve current data using get or get-config
        if (dataSource == null) {
          try {
            dataElements = subtreeFilter.isEmpty() ? session.get() : session.get(subtreeFilter);
          } catch (NetconfException.RPCException e) {
            e.printStackTrace();
            Notification.show("The device cowardly refused to send operational data, thus " +
                "displaying configuration only. You may use 'Show Schemas' to go back, " +
                "select individual supported schemas and try 'Show Data' again.", Notification.Type.ERROR_MESSAGE);
            dataElements = subtreeFilter.isEmpty() ? session.getConfig(Netconf.Datastore.RUNNING, command)
                : session.getConfig(Netconf.Datastore.RUNNING, subtreeFilter, command);
          }
          dataQuery = newQuery;
        } else {
          dataElements = subtreeFilter.isEmpty() ? session.getConfig(dataSource, command)
              : session.getConfig(dataSource, subtreeFilter, command);
        }
      } catch (NetconfException e) {
        e.printStackTrace();
        Notification.show("Failed to get data: " + e.getMessage(), Notification.Type.ERROR_MESSAGE);
      }
    }

    // Collect NETCONF data for tree display
    TreeData<XMLElement> data = new TreeData<>();
    for (XMLElement element : dataElements)
      addXMLToTree(data, element, null, moduleQuery, fieldQuery);

    // Create data provider for tree and define sorting order
    TreeDataProvider<XMLElement> dataProvider = new TreeDataProvider<>(data);
    dataProvider.setSortComparator(Comparator.comparing(XMLElement::getName)::compare);
    dataTree.setDataProvider(dataProvider);

    int remain = 100;

    // Expand up to 50 direct filter matches from data tree
    if (moduleFilter.isEmpty() && fieldFilter.isEmpty()) {
      for (WrappedYangNode node : schemaTree.getSelectedItems()) {
        String path = node.getSensorPath(false, null);
        List<String> paths = Arrays.asList(path.substring(path.indexOf(':') + 1).split("/"));
        remain = expandXMLSelected(dataTree, data.getRootItems(), paths, remain);
      }
    }

    for (XMLElement element : data.getRootItems())
      remain = applyXMLExpanded(dataTree, element, remain);

    if (remain <= 0)
      Notification.show("Too many results! They are all shown, but only 100 have been auto-expanded.",
          Notification.Type.TRAY_NOTIFICATION);

    return dataTree;
  }

  // Transform XML data to a Vaadin treedata object
  private static boolean addXMLToTree(TreeData<XMLElement> data, XMLElement element, XMLElement parent,
      Collection<String> nodeQuery, Collection<String> valueQuery) {
    String name = element.getName().toLowerCase();
    boolean nodeOkay = nodeQuery.stream().filter(name::contains).count() == nodeQuery.size();
    boolean valueOkay = valueQuery.isEmpty();
    boolean okay = false;

    // Add element to tree
    data.addItem(parent, element);

    // Add dummy XML attributes to mark expansion of nodes based on filters
    if (parent == null)
      element.withAttribute("root", "1");
    else if (!nodeQuery.isEmpty() || !valueQuery.isEmpty())
      parent.withAttribute("expand", "1");

    // Once we have a match for node filter, we want all children to be visible, so
    // clear node filter when recursing
    if (nodeOkay && !nodeQuery.isEmpty())
      nodeQuery = Collections.emptyList();

    // For value filter expand child nodes with matching terms
    for (XMLElement child : element) {
      String childText = child.stream().findAny().isPresent() ? null : child.getText().toLowerCase();
      if (childText != null && !valueQuery.isEmpty() &&
          valueQuery.stream().filter(childText::contains).count() == valueQuery.size()) {
        element.withAttribute("expand", "1");
        valueQuery = Collections.emptyList();
        break;
      }
    }

    // Recurse for each child
    for (XMLElement child : element)
      if (addXMLToTree(data, child, element, nodeQuery, valueQuery))
        okay = true;

    okay = okay || (valueOkay && nodeOkay);

    // If we are filtered by node or value filter and none of our children are
    // visible, remove ourselve
    if (!okay || (parent != null && containsCombination(parent.getName(), element.getName()))) {
      data.removeItem(element);
    }

    return okay;
  }

  // Recursively apply element expansion to a tree based on meta-attributes set by
  // addXMLToTree
  private static int applyXMLExpanded(Tree<XMLElement> tree, XMLElement element, int limit) {
    if (element.getAttribute("expand").equals("1") && limit > 0) {
      int limitBefore = limit;
      tree.expand(element);

      for (XMLElement child : tree.getTreeData().getChildren(element))
        limit = applyXMLExpanded(tree, child, limit);

      if (limit == limitBefore)
        --limit;
    }
    return limit;
  }

  // Apply YANG schema filters to data tree
  private static int expandXMLSelected(Tree<XMLElement> tree, Iterable<XMLElement> elements, List<String> path,
      int limit) {
    if (path.size() < 1 || limit < 1)
      return limit;

    path = new LinkedList<>(path);
    String hop = path.remove(0);
    for (XMLElement element : elements) {
      int limitBefore = limit;
      if (element.getName().equals(hop)) {
        tree.expand(element);
        limit = expandXMLSelected(tree, tree.getTreeData().getChildren(element), new LinkedList<>(path), limit);
      }
      if (limit == limitBefore)
        --limit;
    }
    return limit;
  }

  // Show detail table for a selected YANG schema node
  void showYangNode(WrappedYangNode node) {
    selectedNode = node;

    LinkedList<AbstractMap.SimpleEntry<String, String>> parameters = new LinkedList<>();
    parameters.add(new AbstractMap.SimpleEntry<>("Name", node.getName()));
    parameters.add(new AbstractMap.SimpleEntry<>("Namespace", node.getNamespace()));
    parameters.add(new AbstractMap.SimpleEntry<>("Type", node.getType() + " (" +
        (node.isConfiguration() ? "configuration" : "operational") + ")"));

    String type = node.getDataType();
    if (!type.isEmpty())
      parameters.add(new AbstractMap.SimpleEntry<>("Data Type", type));

    String keys = node.getKeys().collect(Collectors.joining(" "));
    if (!keys.isEmpty())
      parameters.add(new AbstractMap.SimpleEntry<>("Keys", keys));

    parameters.add(new AbstractMap.SimpleEntry<>("XPath", node.getXPath()));
    parameters.add(new AbstractMap.SimpleEntry<>("Sensor Path", node.getSensorPath(false, null)));
    parameters.add(new AbstractMap.SimpleEntry<>("Filter Path", node.getSensorPath(true, selectedData)));
    parameters.add(new AbstractMap.SimpleEntry<>("Maagic Path", node.getMaagic(false)));
    parameters.add(new AbstractMap.SimpleEntry<>("Maagic QPath", node.getMaagic(true)));
  }

  public boolean searchModels(String moduleFilter, String nodeFilter) {
    Tree<WrappedYangNode> tree = showSchemaTree(moduleFilter, nodeFilter);
    treePanel.setContent(tree);
    return tree.getTreeData().getRootItems().size() > 0;
  }

  @Override
  public void enter(ViewChangeEvent event) {

  }

  @Override
  public void beforeLeave(ViewBeforeLeaveEvent event) {
    try {
      client.close();
    } catch (NetconfException e) {
      e.printStackTrace();
    }
  }

  private void showConfig() {

    Window window = new Window("Settings and Configuration");
    window.setModal(true);
    window.setWidth("300px");
    window.setHeight("200px");
    window.setResizable(false);

    VerticalLayout layout = new VerticalLayout();
    layout.setMargin(true);
    layout.setSpacing(true);
    layout.setSizeFull();

    Button switchMode = new Button("Switch to Dev Mode", VaadinIcons.EXCHANGE);
    switchMode.addStyleName(ValoTheme.BUTTON_PRIMARY);
    switchMode.addClickListener(x -> {
      if (callback != null) {
        callback.onMessageReceived("main");
      }
      window.close();
    });

    dataCommandCheckBox.addStyleName("data-switch-checkbox");
    dataCommandCheckBox.addValueChangeListener(event -> {
      command = event.getValue().booleanValue() ? "get-config" : "get";
    });

    layout.addStyleName("padding-top-10");
    layout.addComponents(multiCheckBox, dataCommandCheckBox, switchMode);

    window.setContent(layout);
    UI.getCurrent().addWindow(window);
  }

  private void refreschSchemas() {

    UI ui = UI.getCurrent();

    // Render loading window
    Window loadingWindow = new Window();
    loadingWindow.setModal(true);
    loadingWindow.setResizable(false);
    loadingWindow.setClosable(false);
    loadingWindow.setDraggable(false);
    loadingWindow.setWidth("900px");
    loadingWindow.setHeight("75px");

    HorizontalLayout layout = new HorizontalLayout();
    layout.setMargin(true);
    layout.setSizeFull();

    ProgressBar progressBar = new ProgressBar();
    progressBar.setIndeterminate(true);
    progressBar.setWidth("150px");
    Label label = new Label("Connecting...");
    label.addStyleName(ValoTheme.LABEL_BOLD);
    layout.addComponents(progressBar, label);
    layout.setComponentAlignment(progressBar, Alignment.MIDDLE_LEFT);
    layout.setComponentAlignment(label, Alignment.MIDDLE_LEFT);
    layout.setExpandRatio(label, 1.0f);

    loadingWindow.setContent(layout);
    ui.addWindow(loadingWindow);
    ui.push();

    NetconfYangParser yangParser = new NetconfYangParser();
    progressBar.setIndeterminate(false);

    Properties properties = new Properties();
    try (InputStream input = new FileInputStream("/etc/excelforeyangexplorer.conf")) {
      properties.load(input);
      String yangcachePath = properties.getProperty("YANGCACHE_DIR", "/var/cache/jetty9/webapps/yangcache");
      String filePath = new File(yangcachePath).toString();
      yangParser.setCacheDirectory(filePath);
    } catch (IOException ex) {
      yangParser.setCacheDirectory(new File("..", "yangcache").toString());
    }

    try (NetconfSession session = this.client.createSession()) {
      Map<String, String> schemas = yangParser.getAvailableSchemas(session);

      yangParser.retrieveSchemas(session, schemas, (iteration, identifier, version, error) -> {
        label.setValue(String.format("Retrieving schema %s@%s: %s",
            identifier, version, (error != null) ? error.getMessage() : "success"));
        progressBar.setValue(((float) iteration) / schemas.size());
        ui.push();
      }, true);

      // Actually parse the YANG models using ODL yangtools
      label.setValue(String.format("Parsing schemas. This may take a minute..."));
      progressBar.setIndeterminate(true);
      ui.push();

      yangParser.parse();

      if (yangParser.getSchemaContext() == null) {
        Notification.show("Failed to parse schemas: no valid YANG models found!",
            Notification.Type.ERROR_MESSAGE);
      }
    } catch (Exception e) {
      Notification.show(
          "Failed to retrieve schemas: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()),
          Notification.Type.ERROR_MESSAGE);
      e.printStackTrace();
    }

    loadingWindow.close();
    ui.removeWindow(loadingWindow);
  }

  private void traverseAllChildNodes(WrappedYangNode node) {
    if (node.isKey()) {
      parentKeyList.add(new ParentKey(node.getParent().getName(), node.getName()));
    }
    node.getChildren().forEach(child -> {
      traverseAllChildNodes(child);
    });
  }

  public void setMessageCallback(MessageCallback callback) {
    this.callback = callback;
  }

  private String getNodeTooltipString(WrappedYangNode node) {
    String nodeDescription = node.getDescription();
    String toolTipHtmlString = "<pre style='font-family: monospace;'>";
    String[] lines = nodeDescription.split("\n");
    for (String line : lines) {
      toolTipHtmlString = toolTipHtmlString.concat(line);
      toolTipHtmlString = toolTipHtmlString.concat("<br>");
    }
    toolTipHtmlString = toolTipHtmlString.concat("</pre>");
    return nodeDescription.isEmpty() ? null : toolTipHtmlString;
  }

  private boolean isValidTimeInterval(String inputString) {
    try {
      long parsedValue = Long.parseLong(inputString, 16);
      long maxValue = 0x7FFFFFFFFFFFFFFFL;
      return parsedValue < maxValue;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  class ParentKey {
    private String parent;
    private String key;

    public ParentKey(String parent, String key) {
      this.parent = parent;
      this.key = key;
    }

    public String getParent() {
      return parent;
    }

    public void setParent(String parent) {
      this.parent = parent;
    }

    public String getKey() {
      return key;
    }

    public void setKey(String key) {
      this.key = key;
    }
  }

  // Method to check if a combination exists in the ArrayList
  public static boolean containsCombination(String targetParent, String targetKey) {

    for (ParentKey item : parentKeyList) {
      if (item.getParent().equals(targetParent) && item.getKey().equals(targetKey)) {
        return true; // Combination exists
      }
    }
    return false; // Combination does not exist
  }
}
