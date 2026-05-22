/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.runelite.client.config.ConfigManager
 *  net.runelite.client.plugins.microbot.util.inventory.InteractOrder
 *  net.runelite.client.ui.FontManager
 */
package net.runelite.client.plugins.microbot.irkedminer.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.microbot.irkedminer.IrkedMinerConfig;
import net.runelite.client.plugins.microbot.irkedminer.data.Ores;
import net.runelite.client.plugins.microbot.irkedminer.priority.BatchTierOption;
import net.runelite.client.plugins.microbot.util.inventory.InteractOrder;
import net.runelite.client.ui.FontManager;

public class IrkedMinerInlineConfigPanel
extends JPanel {
    private static final Color PAGE_BG = new Color(20, 21, 24);
    private static final Color CARD_TOP = new Color(35, 33, 29);
    private static final Color CARD_BOTTOM = new Color(26, 25, 22);
    private static final Color HERO_TOP = new Color(42, 36, 28);
    private static final Color HERO_BOTTOM = new Color(29, 27, 22);
    private static final Color BORDER_OUTER = new Color(88, 75, 56);
    private static final Color BORDER_INNER = new Color(55, 46, 34);
    private static final Color DIVIDER = new Color(72, 61, 45);
    private static final Color TITLE = new Color(255, 201, 108);
    private static final Color SUBTITLE = new Color(202, 186, 156);
    private static final Color LABEL = new Color(225, 215, 191);
    private static final Color VALUE = new Color(250, 242, 216);
    private static final Color INPUT_BG = new Color(30, 30, 28);
    private static final Color INPUT_BORDER = new Color(74, 63, 48);
    private static final Color INPUT_FOCUS = new Color(165, 127, 62);
    private static final Color ACCENT_MINING = new Color(99, 206, 141);
    private static final Color ACCENT_BATCH = new Color(255, 196, 91);
    private static final Color ACCENT_INV = new Color(118, 182, 240);
    private static final Color ACCENT_WORLD = new Color(248, 152, 95);
    private static final Font TITLE_FONT = FontManager.getRunescapeBoldFont().deriveFont(22.0f);
    private static final Font SECTION_FONT = FontManager.getRunescapeBoldFont().deriveFont(14.0f);
    private static final Font BODY_FONT = FontManager.getRunescapeSmallFont().deriveFont(13.0f);
    private static final Font SMALL_FONT = FontManager.getRunescapeSmallFont().deriveFont(12.0f);
    private static final Font BADGE_FONT = FontManager.getRunescapeSmallFont().deriveFont(1, 11.0f);
    private final IrkedMinerConfig config;
    private final ConfigManager configManager;
    private final JComboBox<Ores> targetOre = new JComboBox<Ores>(Ores.values());
    private final JSpinner distanceToStray = new JSpinner(new SpinnerNumberModel(20, 1, 50, 1));
    private final JCheckBox enableBatchMode = new JCheckBox();
    private final JComboBox<BatchTierOption> tier1 = new JComboBox<BatchTierOption>(BatchTierOption.values());
    private final JComboBox<BatchTierOption> tier2 = new JComboBox<BatchTierOption>(BatchTierOption.values());
    private final JComboBox<BatchTierOption> tier3 = new JComboBox<BatchTierOption>(BatchTierOption.values());
    private final JComboBox<BatchTierOption> tier4 = new JComboBox<BatchTierOption>(BatchTierOption.values());
    private final JCheckBox useBank = new JCheckBox();
    private final JCheckBox useDepositBox = new JCheckBox();
    private final JCheckBox dropGems = new JCheckBox();
    private final JTextField itemsToKeep = new JTextField();
    private final JComboBox<InteractOrder> dropOrder = new JComboBox<InteractOrder>(InteractOrder.values());
    private final JCheckBox worldHop = new JCheckBox();
    private final JCheckBox debugLogging = new JCheckBox();
    private boolean reloading;

    public IrkedMinerInlineConfigPanel(IrkedMinerConfig config, ConfigManager configManager) {
        this.config = config;
        this.configManager = configManager;
        this.setLayout(new BorderLayout());
        this.setBackground(PAGE_BG);
        this.setBorder(new EmptyBorder(0, 0, 0, 0));
        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, 1));
        contentPanel.setBackground(PAGE_BG);
        contentPanel.setBorder(new EmptyBorder(8, 8, 10, 8));
        contentPanel.add(this.createHeroSection());
        contentPanel.add(Box.createVerticalStrut(12));
        contentPanel.add(this.createMiningCard());
        contentPanel.add(Box.createVerticalStrut(12));
        contentPanel.add(this.createBatchMiningCard());
        contentPanel.add(Box.createVerticalStrut(12));
        contentPanel.add(this.createInventoryCard());
        contentPanel.add(Box.createVerticalStrut(12));
        contentPanel.add(this.createWorldCard());
        contentPanel.add(Box.createVerticalStrut(12));
        contentPanel.add(this.createDebugCard());
        contentPanel.add(Box.createVerticalStrut(20));
        this.add((Component)contentPanel, "North");
        this.loadConfigValues();
        this.attachListeners();
    }

    private JPanel createHeroSection() {
        JPanel hero = new JPanel(){

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D)g.create();
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                GradientPaint gradient = new GradientPaint(0.0f, 0.0f, HERO_TOP, 0.0f, this.getHeight(), HERO_BOTTOM);
                g2d.setPaint(gradient);
                g2d.fillRoundRect(0, 0, this.getWidth(), this.getHeight(), 8, 8);
                g2d.setColor(BORDER_OUTER);
                g2d.drawRoundRect(0, 0, this.getWidth() - 1, this.getHeight() - 1, 8, 8);
                g2d.setColor(BORDER_INNER);
                g2d.drawRoundRect(1, 1, this.getWidth() - 3, this.getHeight() - 3, 7, 7);
                g2d.dispose();
            }
        };
        hero.setLayout(new BorderLayout());
        hero.setOpaque(false);
        hero.setBorder(new EmptyBorder(18, 20, 18, 20));
        hero.setPreferredSize(new Dimension(0, 80));
        hero.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));
        JPanel titlePanel = new JPanel(new BorderLayout());
        titlePanel.setOpaque(false);
        JLabel titleLabel = new JLabel("IrkedMiner");
        titleLabel.setFont(TITLE_FONT);
        titleLabel.setForeground(TITLE);
        titlePanel.add((Component)titleLabel, "West");
        JLabel versionBadge = this.createVersionBadge();
        titlePanel.add((Component)versionBadge, "East");
        hero.add((Component)titlePanel, "North");
        return hero;
    }

    private JLabel createVersionBadge() {
        JLabel badge = new JLabel("v1.1.2 \u2022 Batch Mining"){

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D)g.create();
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.setColor(new Color(60, 55, 45, 200));
                g2d.fillRoundRect(0, 0, this.getWidth(), this.getHeight(), 5, 5);
                g2d.setColor(new Color(100, 90, 70, 150));
                g2d.drawRoundRect(0, 0, this.getWidth() - 1, this.getHeight() - 1, 5, 5);
                g2d.dispose();
                super.paintComponent(g);
            }
        };
        badge.setFont(BADGE_FONT);
        badge.setForeground(ACCENT_BATCH);
        badge.setOpaque(false);
        badge.setBorder(new EmptyBorder(4, 10, 4, 10));
        return badge;
    }

    private JPanel createMiningCard() {
        ConfigCard card = new ConfigCard("Mining", ACCENT_MINING);
        card.addFormRow("Target Ore", this.targetOre);
        card.addFormRow("Distance to Stray", this.distanceToStray);
        return card;
    }

    private JPanel createBatchMiningCard() {
        ConfigCard card = new ConfigCard("Batch Mining Mode", ACCENT_BATCH);
        card.addCheckboxRow("Enable Batch Mining Mode", this.enableBatchMode);
        card.addSpacer(6);
        JPanel tierGrid = this.createTierGrid();
        card.addFullWidthComponent(tierGrid);
        card.addSpacer(8);
        card.addInfoBox("Lock to first available Priority, then advance downward only.");
        return card;
    }

    private JPanel createTierGrid() {
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = 2;
        gbc.insets = new Insets(4, 0, 4, 0);
        gbc.weightx = 1.0;
        gbc.gridx = 0;
        gbc.gridy = 0;
        grid.add((Component)this.createTierPanel("PRIORITY 1", "Highest Priority", this.tier1, 1), gbc);
        gbc.gridy = 1;
        grid.add((Component)this.createTierPanel("PRIORITY 2", "Second Priority", this.tier2, 2), gbc);
        gbc.gridy = 2;
        grid.add((Component)this.createTierPanel("PRIORITY 3", "Third Priority", this.tier3, 3), gbc);
        gbc.gridy = 3;
        grid.add((Component)this.createTierPanel("PRIORITY 4", "Final Fallback", this.tier4, 4), gbc);
        return grid;
    }

    private JPanel createTierPanel(String tierName, String description, JComboBox<BatchTierOption> combo, int tierNum) {
        JPanel panel = new JPanel();
        panel.setLayout(new BorderLayout(6, 6));
        panel.setBackground(new Color(40, 38, 34, 150));
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(74, 63, 48, 150), 1), new EmptyBorder(10, 10, 10, 10)));
        JLabel label = new JLabel(tierName + " \u2022 " + description);
        label.setFont(SMALL_FONT);
        label.setForeground(new Color(153, 153, 153));
        panel.add((Component)label, "North");
        this.styleComboBox(combo);
        panel.add(combo, "Center");
        return panel;
    }

    private JPanel createInventoryCard() {
        ConfigCard card = new ConfigCard("Inventory Management", ACCENT_INV);
        card.addCheckboxRow("Use Bank", this.useBank);
        card.addCheckboxRow("Use Deposit Box", this.useDepositBox);
        card.addCheckboxRow("Drop Uncut Gems", this.dropGems);
        card.addFormRow("Items to Keep", this.itemsToKeep);
        card.addFormRow("Drop Order", this.dropOrder);
        return card;
    }

    private JPanel createWorldCard() {
        ConfigCard card = new ConfigCard("World Management", ACCENT_WORLD);
        card.addCheckboxRow("Enable World Hopping", this.worldHop);
        card.addSpacer(6);
        card.addInfoBox("Hop worlds when rocks are depleted or occupied.");
        return card;
    }

    private JPanel createDebugCard() {
        ConfigCard card = new ConfigCard("Debug Options", new Color(150, 150, 150));
        card.addCheckboxRow("Enable Debug Logging", this.debugLogging);
        return card;
    }

    private void styleComboBox(JComboBox<?> combo) {
        combo.setFont(BODY_FONT);
        combo.setForeground(VALUE);
        combo.setBackground(INPUT_BG);
        combo.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(INPUT_BORDER, 1), new EmptyBorder(5, 8, 5, 8)));
        combo.setRenderer(new DefaultListCellRenderer(){

            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel)super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                label.setFont(BODY_FONT);
                label.setBorder(new EmptyBorder(6, 10, 6, 10));
                if (isSelected) {
                    label.setBackground(INPUT_FOCUS);
                    label.setForeground(Color.WHITE);
                } else {
                    label.setBackground(INPUT_BG);
                    label.setForeground(VALUE);
                }
                return label;
            }
        });
    }

    private void styleTextField(JTextField field) {
        field.setFont(BODY_FONT);
        field.setForeground(VALUE);
        field.setBackground(INPUT_BG);
        field.setCaretColor(VALUE);
        field.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(INPUT_BORDER, 1), new EmptyBorder(6, 10, 6, 10)));
    }

    private void styleSpinner(JSpinner spinner) {
        spinner.setFont(BODY_FONT);
        JComponent editor = spinner.getEditor();
        if (editor instanceof JSpinner.DefaultEditor) {
            JFormattedTextField textField = ((JSpinner.DefaultEditor)editor).getTextField();
            textField.setFont(BODY_FONT);
            textField.setForeground(VALUE);
            textField.setBackground(INPUT_BG);
            textField.setCaretColor(VALUE);
            textField.setBorder(new EmptyBorder(6, 10, 6, 10));
        }
        spinner.setBorder(BorderFactory.createLineBorder(INPUT_BORDER, 1));
    }

    private void styleCheckbox(JCheckBox checkbox) {
        checkbox.setFont(BODY_FONT);
        checkbox.setForeground(LABEL);
        checkbox.setOpaque(false);
        checkbox.setFocusPainted(false);
    }

    private void loadConfigValues() {
        this.reloading = true;
        this.targetOre.setSelectedItem((Object)this.config.targetOre());
        this.distanceToStray.setValue(this.config.distanceToStray());
        this.enableBatchMode.setSelected(this.config.enablePriorityMining());
        this.tier1.setSelectedItem((Object)this.config.batchTier1());
        this.tier2.setSelectedItem((Object)this.config.batchTier2());
        this.tier3.setSelectedItem((Object)this.config.batchTier3());
        this.tier4.setSelectedItem((Object)this.config.batchTier4());
        this.useBank.setSelected(this.config.useBank());
        this.useDepositBox.setSelected(this.config.useDepositBox());
        this.dropGems.setSelected(this.config.dropUncutGems());
        this.itemsToKeep.setText(this.config.itemsToKeep());
        this.dropOrder.setSelectedItem(this.config.interactOrder());
        this.worldHop.setSelected(this.config.enableWorldHopping());
        this.debugLogging.setSelected(this.config.enableDebugLogging());
        this.reloading = false;
    }

    private void attachListeners() {
        this.targetOre.addActionListener(e -> this.saveIfNotReloading(() -> this.configManager.setConfiguration("IrkedMiner", "targetOre", this.targetOre.getSelectedItem())));
        this.distanceToStray.addChangeListener(e -> this.saveIfNotReloading(() -> this.configManager.setConfiguration("IrkedMiner", "distanceToStray", this.distanceToStray.getValue())));
        this.enableBatchMode.addActionListener(e -> this.saveIfNotReloading(() -> this.configManager.setConfiguration("IrkedMiner", "enablePriorityMining", (Object)this.enableBatchMode.isSelected())));
        this.tier1.addActionListener(e -> this.saveIfNotReloading(() -> this.configManager.setConfiguration("IrkedMiner", "batchTier1", this.tier1.getSelectedItem())));
        this.tier2.addActionListener(e -> this.saveIfNotReloading(() -> this.configManager.setConfiguration("IrkedMiner", "batchTier2", this.tier2.getSelectedItem())));
        this.tier3.addActionListener(e -> this.saveIfNotReloading(() -> this.configManager.setConfiguration("IrkedMiner", "batchTier3", this.tier3.getSelectedItem())));
        this.tier4.addActionListener(e -> this.saveIfNotReloading(() -> this.configManager.setConfiguration("IrkedMiner", "batchTier4", this.tier4.getSelectedItem())));
        this.useBank.addActionListener(e -> this.saveIfNotReloading(() -> this.configManager.setConfiguration("IrkedMiner", "useBank", (Object)this.useBank.isSelected())));
        this.useDepositBox.addActionListener(e -> this.saveIfNotReloading(() -> this.configManager.setConfiguration("IrkedMiner", "useDepositBox", (Object)this.useDepositBox.isSelected())));
        this.dropGems.addActionListener(e -> this.saveIfNotReloading(() -> this.configManager.setConfiguration("IrkedMiner", "dropUncutGems", (Object)this.dropGems.isSelected())));
        this.itemsToKeep.addActionListener(e -> this.saveIfNotReloading(() -> this.configManager.setConfiguration("IrkedMiner", "itemsToKeep", this.itemsToKeep.getText())));
        this.dropOrder.addActionListener(e -> this.saveIfNotReloading(() -> this.configManager.setConfiguration("IrkedMiner", "interactOrder", this.dropOrder.getSelectedItem())));
        this.worldHop.addActionListener(e -> this.saveIfNotReloading(() -> this.configManager.setConfiguration("IrkedMiner", "enableWorldHopping", (Object)this.worldHop.isSelected())));
        this.debugLogging.addActionListener(e -> this.saveIfNotReloading(() -> this.configManager.setConfiguration("IrkedMiner", "enableDebugLogging", (Object)this.debugLogging.isSelected())));
    }

    private void saveIfNotReloading(Runnable save) {
        if (!this.reloading) {
            save.run();
        }
    }

    private class ConfigCard
    extends JPanel {
        private final GridBagConstraints gbc;
        private int currentRow = 0;

        public ConfigCard(String title, Color accentColor) {
            this.setLayout(new GridBagLayout());
            this.setOpaque(false);
            this.setBorder(new EmptyBorder(0, 0, 0, 0));
            this.gbc = new GridBagConstraints();
            this.gbc.fill = 2;
            this.gbc.weightx = 1.0;
            this.gbc.gridx = 0;
            this.gbc.gridy = 0;
            this.gbc.gridwidth = 2;
            this.gbc.insets = new Insets(0, 0, 0, 0);
            JPanel cardPanel = new JPanel(){

                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2d = (Graphics2D)g.create();
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    GradientPaint gradient = new GradientPaint(0.0f, 0.0f, CARD_TOP, 0.0f, this.getHeight(), CARD_BOTTOM);
                    g2d.setPaint(gradient);
                    g2d.fillRoundRect(0, 0, this.getWidth(), this.getHeight(), 8, 8);
                    g2d.setColor(BORDER_OUTER);
                    g2d.drawRoundRect(0, 0, this.getWidth() - 1, this.getHeight() - 1, 8, 8);
                    g2d.setColor(BORDER_INNER);
                    g2d.drawRoundRect(1, 1, this.getWidth() - 3, this.getHeight() - 3, 7, 7);
                    g2d.dispose();
                }
            };
            cardPanel.setLayout(new GridBagLayout());
            cardPanel.setOpaque(false);
            cardPanel.setBorder(new EmptyBorder(16, 12, 16, 12));
            GridBagConstraints headerGbc = new GridBagConstraints();
            headerGbc.gridx = 0;
            headerGbc.gridy = 0;
            headerGbc.gridwidth = 2;
            headerGbc.anchor = 17;
            headerGbc.insets = new Insets(0, 0, 12, 0);
            JPanel headerPanel = new JPanel(new BorderLayout(8, 0));
            headerPanel.setOpaque(false);
            JPanel accentBar = new JPanel();
            accentBar.setBackground(accentColor);
            accentBar.setPreferredSize(new Dimension(4, 18));
            headerPanel.add((Component)accentBar, "West");
            JLabel titleLabel = new JLabel(title);
            titleLabel.setFont(SECTION_FONT);
            titleLabel.setForeground(VALUE);
            headerPanel.add((Component)titleLabel, "Center");
            cardPanel.add((Component)headerPanel, headerGbc);
            this.add((Component)cardPanel, this.gbc);
            this.gbc.insets = new Insets(4, 0, 4, 0);
            this.setLayout(new BorderLayout());
            this.add((Component)cardPanel, "Center");
            this.currentRow = 1;
        }

        public void addFormRow(String labelText, JComponent component) {
            JPanel rowPanel = new JPanel(new BorderLayout(8, 0));
            rowPanel.setOpaque(false);
            rowPanel.setBorder(new EmptyBorder(6, 0, 6, 0));
            JLabel label = new JLabel(labelText);
            label.setFont(BODY_FONT);
            label.setForeground(LABEL);
            label.setPreferredSize(new Dimension(105, label.getPreferredSize().height));
            rowPanel.add((Component)label, "West");
            if (component instanceof JComboBox) {
                IrkedMinerInlineConfigPanel.this.styleComboBox((JComboBox)component);
            } else if (component instanceof JTextField) {
                IrkedMinerInlineConfigPanel.this.styleTextField((JTextField)component);
            } else if (component instanceof JSpinner) {
                IrkedMinerInlineConfigPanel.this.styleSpinner((JSpinner)component);
            }
            rowPanel.add((Component)component, "Center");
            this.addToCard(rowPanel);
        }

        public void addCheckboxRow(String text, JCheckBox checkbox) {
            JPanel rowPanel = new JPanel(new BorderLayout());
            rowPanel.setOpaque(false);
            rowPanel.setBorder(new EmptyBorder(6, 0, 6, 0));
            checkbox.setText(text);
            IrkedMinerInlineConfigPanel.this.styleCheckbox(checkbox);
            rowPanel.add((Component)checkbox, "West");
            this.addToCard(rowPanel);
        }

        public void addFullWidthComponent(JComponent component) {
            this.addToCard(component);
        }

        public void addInfoBox(String text) {
            JPanel infoBox = new JPanel(new BorderLayout(8, 0));
            infoBox.setBackground(new Color(99, 206, 141, 20));
            infoBox.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(99, 206, 141, 76), 1), new EmptyBorder(8, 10, 8, 10)));
            JLabel infoIcon = new JLabel("i");
            infoIcon.setFont(SMALL_FONT.deriveFont(1));
            infoIcon.setForeground(ACCENT_MINING);
            infoIcon.setHorizontalAlignment(0);
            infoIcon.setPreferredSize(new Dimension(16, 16));
            infoIcon.setOpaque(true);
            infoIcon.setBackground(new Color(99, 206, 141, 76));
            infoIcon.setBorder(BorderFactory.createLineBorder(ACCENT_MINING, 1));
            JLabel textLabel = new JLabel("<html>" + text + "</html>");
            textLabel.setFont(SMALL_FONT);
            textLabel.setForeground(new Color(158, 242, 190));
            infoBox.add((Component)infoIcon, "West");
            infoBox.add((Component)textLabel, "Center");
            this.addToCard(infoBox);
        }

        public void addSpacer(int height) {
            JPanel spacer = new JPanel();
            spacer.setOpaque(false);
            spacer.setPreferredSize(new Dimension(0, height));
            spacer.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
            this.addToCard(spacer);
        }

        private void addToCard(JComponent component) {
            Component[] components = this.getComponents();
            if (components.length > 0 && components[0] instanceof JPanel) {
                JPanel cardPanel = (JPanel)components[0];
                GridBagConstraints contentGbc = new GridBagConstraints();
                contentGbc.gridx = 0;
                contentGbc.gridy = this.currentRow++;
                contentGbc.gridwidth = 2;
                contentGbc.fill = 2;
                contentGbc.weightx = 1.0;
                contentGbc.insets = new Insets(2, 0, 2, 0);
                cardPanel.add((Component)component, contentGbc);
            }
        }
    }
}

