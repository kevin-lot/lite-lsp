import QtQuick

Rectangle {
    id: card
    property string title: ""
    property int count: 0
    signal picked(string name)

    radius: 4
    color: Theme.accent
    implicitWidth: 120
    implicitHeight: 48

    Text {
        anchors.centerIn: parent
        text: card.title + " (" + card.count + ")"
        color: "white"
    }

    MouseArea {
        anchors.fill: parent
        onClicked: card.picked(card.title)
    }
}
