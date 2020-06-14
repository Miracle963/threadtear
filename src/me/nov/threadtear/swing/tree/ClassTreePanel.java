package me.nov.threadtear.swing.tree;

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.jar.JarEntry;

import javax.swing.*;
import javax.swing.tree.*;

import com.github.weisj.darklaf.components.OverlayScrollPane;
import me.nov.threadtear.swing.TitledPanel;
import org.apache.commons.io.FilenameUtils;
import org.objectweb.asm.tree.ClassNode;

import me.nov.threadtear.Threadtear;
import me.nov.threadtear.execution.Clazz;
import me.nov.threadtear.io.*;
import me.nov.threadtear.swing.Utils;
import me.nov.threadtear.swing.analysis.InstructionAnalysis;
import me.nov.threadtear.swing.dialog.FileInfo;
import me.nov.threadtear.swing.frame.AnalysisFrame;
import me.nov.threadtear.swing.handler.*;
import me.nov.threadtear.swing.tree.component.ClassTreeNode;
import me.nov.threadtear.swing.tree.renderer.ClassTreeCellRenderer;
import me.nov.threadtear.util.format.Strings;

public class ClassTreePanel extends JPanel implements ILoader {
  private static final long serialVersionUID = 1L;
  private Threadtear threadtear;
  public File inputFile;
  public List<Clazz> classes;
  public DefaultTreeModel model;
  private ClassTree tree;
  private TitledPanel outerPanel;

  private JButton obfAnalysis;
  private JButton analyze;
  private JButton fileInfo;
  private JButton ignore;

  public ClassTreePanel(Threadtear threadtear) {
    this.threadtear = threadtear;
    this.setLayout(new BorderLayout());
    this.add(outerPanel = Utils.withTitleAndBorder("Class list", new OverlayScrollPane(tree = new ClassTree())),
            BorderLayout.CENTER);
    this.add(Utils.pad(createButtons(), 8,0,8,0), BorderLayout.SOUTH);
    this.setTransferHandler(new JarDropHandler(this));
  }

  @Override
  public Dimension getMinimumSize() {
    Dimension minSize = super.getMinimumSize();
    minSize.width = 150;
    return minSize;
  }

  private JPanel createButtons() {
    JPanel panel = new JPanel();
    panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
    obfAnalysis = new JButton("Full analysis", Utils.getIcon("res/analysis.svg", true));
    obfAnalysis.addActionListener(l -> {
      threadtear.logFrame.setVisible(true);
      new Thread(() -> InstructionAnalysis.analyze(classes)).start();
    });
    obfAnalysis.setEnabled(false);
    panel.add(obfAnalysis);
    analyze = new JButton("Analyze code", Utils.getIcon("res/decompile.svg", true));
    analyze.addActionListener(l -> {
      ClassTreeNode tn = (ClassTreeNode) tree.getLastSelectedPathComponent();
      if (tn != null && tn.member != null) {
        new AnalysisFrame(inputFile, tn.member).setVisible(true);
      }
    });
    panel.add(analyze);
    analyze.setEnabled(false);
    fileInfo = new JButton("Information", Utils.getIcon("res/file.svg", true));
    fileInfo.addActionListener(l -> {
      ClassTreeNode tn = (ClassTreeNode) tree.getLastSelectedPathComponent();
      if (tn != null && tn.member != null) {
        new FileInfo(ClassTreePanel.this, tn.member).setVisible(true);
      }
    });

    panel.add(fileInfo);
    fileInfo.setEnabled(false);
    ignore = new JButton("Ignore", Utils.getIcon("res/ignore.svg", true));
    ignore.addActionListener(l -> {
      TreePath[] paths = tree.getSelectionPaths();
      for (TreePath path : paths) {
        ClassTreeNode tn = (ClassTreeNode) path.getLastPathComponent();
        ignoreChilds(tn);
      }
      refreshIgnored();
      tree.grabFocus();
    });
    ignore.setEnabled(false);
    panel.add(ignore);
    return panel;
  }

  public void refreshIgnored() {
    if (classes != null) {
      long disabled = classes.stream().filter(c -> !c.transform).count();
      outerPanel.setTitle(Strings.min(inputFile.getName(), 40) + " - " + classes.size() + " classes (" + disabled + " ignored)");
    }
    repaint();
  }

  public void ignore(String className) {
    classes.stream().filter(c -> c.node.name.equals(className)).forEach(c -> c.transform = false);
  }

  public void updateAllNames(ClassTreeNode root) {
    root.updateClassName();
    for (int i = 0; i < root.getChildCount(); i++) {
      ClassTreeNode child = (ClassTreeNode) root.getChildAt(i);
      updateAllNames(child);
    }
  }

  private void ignoreChilds(ClassTreeNode node) {
    if (node.member != null) {
      node.member.transform = !node.member.transform;
      node.updateClassName();
    } else {
      for (int i = 0; i < node.getChildCount(); i++) {
        ClassTreeNode child = (ClassTreeNode) node.getChildAt(i);
        ignoreChilds(child);
      }
    }
  }

  public class ClassTree extends JTreeWithHint {
    private static final long serialVersionUID = 1L;

    public ClassTree() {
      super("Drag a jar or class file here");
      this.setRootVisible(false);
      this.setShowsRootHandles(true);
      this.setFocusable(true);
      this.setCellRenderer(new ClassTreeCellRenderer());
      model = new DefaultTreeModel(new ClassTreeNode(""));
      this.setModel(model);
      ToolTipManager.sharedInstance().registerComponent(this);
      this.getSelectionModel().setSelectionMode(TreeSelectionModel.CONTIGUOUS_TREE_SELECTION);
      this.addMouseListener(new MouseAdapter() {
        @Override
        public void mouseClicked(MouseEvent e) {
          if (e.getClickCount() == 2) {
            ClassTreeNode tn = (ClassTreeNode) getLastSelectedPathComponent();
            if (tn != null && tn.member != null) {
              new AnalysisFrame(inputFile, tn.member).setVisible(true);
            }
          }
        }
      });
      this.addTreeSelectionListener(l -> {
        ClassTreeNode tn = (ClassTreeNode) getLastSelectedPathComponent();
        boolean selected = tn != null;
        analyze.setEnabled(selected && tn.member != null);
        fileInfo.setEnabled(selected && tn.member != null);
        ignore.setEnabled(selected);
      });
    }
  }

  @Override
  public void onFileDrop(File input) {
    Threadtear.logger.info("Loading class files from {}", input.getAbsolutePath());
    threadtear.statusBar.runWithLoadIndicator("Loading class file(s)...", () -> {
      try {
        String type = FilenameUtils.getExtension(input.getAbsolutePath());
        this.inputFile = input;
        this.loadFile(type);
        loadTree(classes);
        refreshIgnored();
        model.reload();
        obfAnalysis.setEnabled(true);
        threadtear.configPanel.run.setEnabled(true);
        threadtear.configPanel.save.setEnabled(true);
        int classesCount = classes.size();
        Threadtear.logger.info("Loaded {} class file(s)", classesCount);
        threadtear.statusBar.setMessage(String.format("Loaded %d class file(s)", classesCount));
      } catch (Exception e) {
        e.printStackTrace();
      }
    });
  }

  private void loadFile(String type) {
    try {
      switch (type) {
        case "jar":
          this.classes = JarIO.loadClasses(inputFile);
          if (classes.stream().anyMatch(c -> c.oldEntry.getCertificates() != null)) {
            JOptionPane.showMessageDialog(this,
                    "<html" + ">Warning: File is signed and" + " may not load correctly if " + "already " +
                            "modified, remove the" + " signature<br>" + "(<tt>META-INF\\MANIFEST" + ".MF</tt" +
                            ">) and certificates " + "(<tt>META-INF\\*.SF/" + ".RSA</tt>) first!",
                    "Signature" + " warning", JOptionPane.WARNING_MESSAGE);
          }
          break;
        case "class":
          ClassNode node = Conversion.toNode(Files.readAllBytes(inputFile.toPath()));
          this.classes =
                  new ArrayList<>(Collections.singletonList(new Clazz(node, new JarEntry(node.name), inputFile)));
          break;
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public void loadTree(List<Clazz> classes) {
    ClassTreeNode root = new ClassTreeNode("");
    model = new DefaultTreeModel(root);
    classes.forEach(c -> {
      String[] packages = c.node.name.split("/");
      if (c.node.name.contains("//") || packages.length >= 256) {
        String last = packages[packages.length - 1];
        boolean valid = last.chars().mapToObj(i -> (char) i).allMatch(Character::isJavaIdentifierPart);
        packages = new String[]{"<html><font " + "color=\"red\">$invalid_name",
                valid ? last : ("<html><font " + "color=\"red\">$" + last.hashCode())};
      }
      addToTree((ClassTreeNode) model.getRoot(), c, packages, 0);
    });
    for (Object n : Collections.list(root.depthFirstEnumeration())) {
      ClassTreeNode node = (ClassTreeNode) n;
      if (!node.isLeaf() && node != root) {
        if (node.getChildCount() == 1) {
          ClassTreeNode child = (ClassTreeNode) node.getChildAt(0);
          if (child.member == null) {
            node.combinePackage(child);
          }
        }
      }
      node.sort();
    }
    tree.setModel(model);
  }

  public void addToTree(ClassTreeNode current, Clazz c, String[] packages, int pckg) {
    String node = packages[pckg];
    if (packages.length - pckg <= 1) {
      current.add(new ClassTreeNode(c));
      return;
    }
    for (int i = 0; i < current.getChildCount(); i++) {

      ClassTreeNode child = (ClassTreeNode) current.getChildAt(i);
      if (child.toString().equals(node) && child.member == null) {
        addToTree(child, c, packages, ++pckg);
        return;
      }
    }
    ClassTreeNode newChild = new ClassTreeNode(node);
    current.add(newChild);
    addToTree(newChild, c, packages, ++pckg);
  }
}
