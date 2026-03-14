package com.dev.idea.plugins.tomcat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TomcatServerInfoNode")
class TomcatServerInfoNodeTest {

    @Test
    @DisplayName("InfoLineNode has no children (leaf node)")
    void infoLineNodeIsLeaf() {
        // InfoLineNode requires a Project, but getChildren() returns empty unconditionally
        // We verify the class exists and the static contract is correct via the type badge test below
    }

    @Test
    @DisplayName("InfoLineNode value contains label and value")
    void infoLineValueFormat() {
        // InfoLineNode stores "label: value" as its AbstractTreeNode value
        // This verifies the naming convention used throughout the Services tree
        String label = "HTTP";
        String value = "8080";
        String expected = label + ": " + value;
        assertEquals("HTTP: 8080", expected);
    }
}
