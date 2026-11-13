import QtQuick
import QtQuick.Layouts

Item {
    id: root
    property int total: 0
    width: 400; height: 200

    function add(n) { total += n }

    ColumnLayout {
        anchors.fill: parent
        spacing: Theme.spacing

        Card {
            id: first
            title: "circles"
            Layout.fillWidth: true
            onPicked: (name) => root.add(1)
            onCountChanged: console.log(count)
        }
        Card {
            title: "squares"
            count: first.count + 1
            Layout.fillWidth: true
        }
    }

    Component.onCompleted: console.log("total", Math.max(total, 0))
}
