package org.jabref.gui.preferences;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;

import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ReadOnlyListWrapper;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;

import org.jabref.Globals;
import org.jabref.gui.DialogService;
import org.jabref.gui.actions.ActionFactory;
import org.jabref.gui.actions.SimpleCommand;
import org.jabref.gui.actions.StandardActions;
import org.jabref.gui.preview.PreviewViewer;
import org.jabref.gui.util.BindingsHelper;
import org.jabref.gui.util.TaskExecutor;
import org.jabref.gui.util.ViewModelListCellFactory;
import org.jabref.logic.citationstyle.CitationStylePreviewLayout;
import org.jabref.logic.citationstyle.PreviewLayout;
import org.jabref.logic.citationstyle.TextBasedPreviewLayout;
import org.jabref.logic.l10n.Localization;
import org.jabref.logic.util.TestEntry;
import org.jabref.model.database.BibDatabaseContext;
import org.jabref.preferences.JabRefPreferences;

import com.airhacks.afterburner.views.ViewLoader;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PreviewTabView extends VBox implements PrefsTab {

    private static final Logger LOGGER = LoggerFactory.getLogger(PreviewTabView.class);

    @FXML private ListView<PreviewLayout> availableListView;
    @FXML private ListView<PreviewLayout> chosenListView;

    @FXML private Button toRightButton;
    @FXML private Button toLeftButton;
    @FXML private Button sortUpButton;
    @FXML private Button sortDownButton;

    @FXML private Label readOnlyLabel;
    @FXML private Button resetDefaultButton;

    @FXML private ScrollPane previewPane;
    @FXML private CodeArea editArea;

    private final ContextMenu contextMenu;

    @Inject private TaskExecutor taskExecutor;
    @Inject private DialogService dialogService;
    private final JabRefPreferences preferences;

    private long lastKeyPressTime;
    private String listSearchTerm;

    private PreviewTabViewModel viewModel;

    private class EditAction extends SimpleCommand {

        private final StandardActions command;

        public EditAction(StandardActions command) { this.command = command; }

        @Override
        public void execute() {
            if (editArea != null) {
                switch (command) {
                    case COPY:
                        editArea.copy();
                        break;
                    case CUT:
                        editArea.cut();
                        break;
                    case PASTE:
                        editArea.paste();
                        break;
                    case SELECT_ALL:
                        editArea.selectAll();
                        break;
                }
                editArea.requestFocus();
            }
        }
    }

    public PreviewTabView(JabRefPreferences preferences) {
        this.preferences = preferences;
        contextMenu = new ContextMenu();
        ViewLoader.view(this)
                  .root(this)
                  .load();
    }

    public void initialize() {
        viewModel = new PreviewTabViewModel(dialogService, preferences, taskExecutor);

        ActionFactory factory = new ActionFactory(Globals.getKeyPrefs());
        contextMenu.getItems().addAll(
                factory.createMenuItem(StandardActions.CUT, new PreviewTabView.EditAction(StandardActions.CUT)),
                factory.createMenuItem(StandardActions.COPY, new PreviewTabView.EditAction(StandardActions.COPY)),
                factory.createMenuItem(StandardActions.PASTE, new PreviewTabView.EditAction(StandardActions.PASTE)),
                factory.createMenuItem(StandardActions.SELECT_ALL, new PreviewTabView.EditAction(StandardActions.SELECT_ALL))
        );
        contextMenu.getStyleClass().add("context-menu");
        editArea.setContextMenu(contextMenu);

        lastKeyPressTime = System.currentTimeMillis();
        listSearchTerm = "";

        availableListView.itemsProperty().bind(viewModel.availableListProperty());
        viewModel.selectedAvailableItemsProperty().bind(new ReadOnlyListWrapper<>(availableListView.getSelectionModel().getSelectedItems()));
        new ViewModelListCellFactory<PreviewLayout>().withText(PreviewLayout::getName).install(availableListView);
        availableListView.setOnKeyTyped(event -> jumpToSearchKey(availableListView, event));

        chosenListView.itemsProperty().bind(viewModel.chosenListProperty());
        viewModel.selectedChosenItemsProperty().bind(new ReadOnlyListWrapper<>(chosenListView.getSelectionModel().getSelectedItems()));
        new ViewModelListCellFactory<PreviewLayout>().withText(PreviewLayout::getName).install(chosenListView);
        chosenListView.setOnKeyTyped(event -> jumpToSearchKey(chosenListView, event));

        toRightButton.disableProperty().bind(availableListView.selectionModelProperty().getValue().selectedItemProperty().isNull());

        BooleanBinding nothingSelectedFromChosen = chosenListView.selectionModelProperty().getValue().selectedItemProperty().isNull();
        toLeftButton.disableProperty().bind(nothingSelectedFromChosen);
        sortUpButton.disableProperty().bind(nothingSelectedFromChosen);
        sortDownButton.disableProperty().bind(nothingSelectedFromChosen);
        contextMenu.getItems().get(0).disableProperty().bind(nothingSelectedFromChosen); // ToDo: should not if readonly
        contextMenu.getItems().get(2).disableProperty().bind(nothingSelectedFromChosen);

        previewPane.setContent(new PreviewViewer(new BibDatabaseContext(), dialogService, Globals.stateManager));
        ((PreviewViewer) previewPane.getContent()).setEntry(TestEntry.getTestEntry());
        ((PreviewViewer) previewPane.getContent()).setLayout(viewModel.getTestLayout());

        editArea.setParagraphGraphicFactory(LineNumberFactory.get(editArea));
        editArea.textProperty().addListener((obs, oldText, newText) ->
                editArea.setStyleSpans(0, computeHighlighting(newText)));

        BindingsHelper.bindBidirectional(editArea.textProperty(), new ReadOnlyListWrapper<>(chosenListView.selectionModelProperty().getValue().getSelectedItems()),
                layoutList -> update(),
                            text -> {
                                if (!viewModel.selectedChosenItemsProperty().getValue().isEmpty()) {
                                    PreviewLayout item = viewModel.selectedChosenItemsProperty().get(0);
                                    if (item instanceof TextBasedPreviewLayout) {
                                        ((TextBasedPreviewLayout)item).setText(editArea.getText().replace("\n", "__NEWLINE__"));
                                    }
                                }
                                update();
                            }
                );

        update();
    }

    private void update() { // ToDo: convert to bindings
        ObservableList<PreviewLayout> layoutList = chosenListView.getSelectionModel().getSelectedItems();
        if (layoutList.isEmpty()) {
            ((PreviewViewer) previewPane.getContent()).setLayout(viewModel.getTestLayout());
            previewPane.visibleProperty().setValue(false);

            editArea.clear();
           // editArea.editableProperty().setValue(false);
            readOnlyLabel.visibleProperty().setValue(false);
            resetDefaultButton.disableProperty().setValue(true);
        } else {
            String previewText;
            PreviewLayout item = layoutList.get(0);

            try {
                ((PreviewViewer) previewPane.getContent()).setLayout(item);
            } catch (StringIndexOutOfBoundsException exception) {
                LOGGER.warn("Parsing error.", exception);
                dialogService.showErrorDialogAndWait(Localization.lang("Parsing error"), Localization.lang("Parsing error") + ": " + Localization.lang("illegal backslash expression"), exception);
            }
            previewPane.visibleProperty().setValue(true);

            if (item instanceof TextBasedPreviewLayout) {
                previewText = ((TextBasedPreviewLayout) item).getText().replace("__NEWLINE__", "\n");
                //editArea.editableProperty().setValue(false);
                readOnlyLabel.visibleProperty().setValue(false);
                resetDefaultButton.disableProperty().setValue(false);
            } else {
                previewText = ((CitationStylePreviewLayout) item).getSource();
                //editArea.editableProperty().setValue(true); // ToDo: Cursor caret disappears
                readOnlyLabel.visibleProperty().setValue(true);
                resetDefaultButton.disableProperty().setValue(true);
            }

            editArea.replaceText(previewText);
            editArea.setParagraphGraphicFactory(LineNumberFactory.get(editArea)); // ToDo: throws NPE on CitationStylePreviewLayout
            editArea.setStyleSpans(0, computeHighlighting(previewText));
        }
    }

    /**
     * XML-Syntax-Highlighting for RichTextFX-Codearea
     * created by (c) Carlos Martins (github: @cemartins)
     * License: BSD-2-Clause
     * see https://github.com/FXMisc/RichTextFX/blob/master/LICENSE
     * and: https://github.com/FXMisc/RichTextFX/blob/master/richtextfx-demos/README.md#xml-editor
     *
     * @param text to parse and highlight
     * @return highlighted span for codeArea
     */
    private StyleSpans<Collection<String>> computeHighlighting(String text) {

        final Pattern XML_TAG = Pattern.compile("(?<ELEMENT>(</?\\h*)(\\w+)([^<>]*)(\\h*/?>))"
                + "|(?<COMMENT><!--[^<>]+-->)");
        final Pattern ATTRIBUTES = Pattern.compile("(\\w+\\h*)(=)(\\h*\"[^\"]+\")");

        final int GROUP_OPEN_BRACKET = 2;
        final int GROUP_ELEMENT_NAME = 3;
        final int GROUP_ATTRIBUTES_SECTION = 4;
        final int GROUP_CLOSE_BRACKET = 5;
        final int GROUP_ATTRIBUTE_NAME = 1;
        final int GROUP_EQUAL_SYMBOL = 2;
        final int GROUP_ATTRIBUTE_VALUE = 3;

        Matcher matcher = XML_TAG.matcher(text);
        int lastKwEnd = 0;
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        while (matcher.find()) {

            spansBuilder.add(Collections.emptyList(), matcher.start() - lastKwEnd);
            if (matcher.group("COMMENT") != null) {
                spansBuilder.add(Collections.singleton("comment"), matcher.end() - matcher.start());
            } else {
                if (matcher.group("ELEMENT") != null) {
                    String attributesText = matcher.group(GROUP_ATTRIBUTES_SECTION);

                    spansBuilder.add(Collections.singleton("tagmark"), matcher.end(GROUP_OPEN_BRACKET) - matcher.start(GROUP_OPEN_BRACKET));
                    spansBuilder.add(Collections.singleton("anytag"), matcher.end(GROUP_ELEMENT_NAME) - matcher.end(GROUP_OPEN_BRACKET));

                    if (!attributesText.isEmpty()) {

                        lastKwEnd = 0;

                        Matcher amatcher = ATTRIBUTES.matcher(attributesText);
                        while (amatcher.find()) {
                            spansBuilder.add(Collections.emptyList(), amatcher.start() - lastKwEnd);
                            spansBuilder.add(Collections.singleton("attribute"), amatcher.end(GROUP_ATTRIBUTE_NAME) - amatcher.start(GROUP_ATTRIBUTE_NAME));
                            spansBuilder.add(Collections.singleton("tagmark"), amatcher.end(GROUP_EQUAL_SYMBOL) - amatcher.end(GROUP_ATTRIBUTE_NAME));
                            spansBuilder.add(Collections.singleton("avalue"), amatcher.end(GROUP_ATTRIBUTE_VALUE) - amatcher.end(GROUP_EQUAL_SYMBOL));
                            lastKwEnd = amatcher.end();
                        }
                        if (attributesText.length() > lastKwEnd) {
                            spansBuilder.add(Collections.emptyList(), attributesText.length() - lastKwEnd);
                        }
                    }

                    lastKwEnd = matcher.end(GROUP_ATTRIBUTES_SECTION);

                    spansBuilder.add(Collections.singleton("tagmark"), matcher.end(GROUP_CLOSE_BRACKET) - lastKwEnd);
                }
            }
            lastKwEnd = matcher.end();
        }
        spansBuilder.add(Collections.emptyList(), text.length() - lastKwEnd);
        return spansBuilder.create();
    }

    public void jumpToSearchKey(ListView<PreviewLayout> list, KeyEvent keypressed) {
        if (keypressed.getCharacter() == null) {
            return;
        }

        if (System.currentTimeMillis() - lastKeyPressTime < 1000) {
            listSearchTerm += keypressed.getCharacter().toLowerCase();
        } else {
            listSearchTerm = keypressed.getCharacter().toLowerCase();
        }

        lastKeyPressTime = System.currentTimeMillis();

        list.getItems().stream().filter(item -> item.getName().toLowerCase().startsWith(listSearchTerm))
                .findFirst().ifPresent(item -> list.scrollTo(item));
    }

    @Override
    public Node getBuilder() {
        return this;
    }

    @Override
    public void setValues() {
        // Done by bindings
    }

    @Override
    public void storeSettings() {
        viewModel.storeSettings();
    }

    @Override
    public boolean validateSettings() {
        return viewModel.validateSettings();
    }

    @Override
    public String getTabName() {
        return Localization.lang("Entry preview");
    }

    public void toRightButtonAction() { viewModel.addToChosen(); }

    public void toLeftButtonAction() { viewModel.removeFromChosen(); }

    public void sortUpButtonAction() { // ToDo: previewPane loads first in chosenList if Preview is moved
        List<Integer> newIndices = viewModel.selectedInChosenUp(chosenListView.getSelectionModel().getSelectedIndices());
        for (int index : newIndices) {
            chosenListView.getSelectionModel().select(index);
        }
        update();
    }

    public void sortDownButtonAction() {
        List<Integer> newIndices = viewModel.selectedInChosenDown(chosenListView.getSelectionModel().getSelectedIndices());
        for (int index : newIndices) {
            chosenListView.getSelectionModel().select(index);
        }
        update();
    }

    public void resetDefaultButtonAction() { // not yet working
        viewModel.resetDefaultStyle();
        update();
    }
}
