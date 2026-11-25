# Ctrl+Click: a word of a step → its definition in steps/shapes.rs; `<sides>` → its column.
Feature: Shapes

  Background:
    Given an empty canvas

  @smoke
  Scenario: Largest shape
    Given a circle of radius 2
    And a square named "tile" of side 3
    When I ask for the largest shape
    Then the answer is "tile"

  Scenario Outline: Counting sides
    Given a <shape> on the canvas
    Then it has <sides> sides

    Examples:
      | shape    | sides |
      | square   | 4     |
      | triangle | 3     |
