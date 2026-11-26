import 'package:playground_mobile/l10n/app_localizations.dart';
import 'package:playground_mobile/src/shape.dart';

class ShapeCard {
  final Shape shape;
  const ShapeCard({required this.shape});

  String get label => switch (shape) {
        Square(side: final s) => 'square of $s',
        Circle(radius: final r) => 'circle of $r',
      };
}

void main() {
  final shapes = <Shape>[const Square(2), const Circle(radius: 1)];
  final card = ShapeCard(shape: largest(shapes));
  for (final shape in shapes) {
    print('${shape.name}: ${shape.area}');
  }
  print(card.label);

  final l10n = AppLocalizations.of(card);
  print('${l10n.appTitle}: ${l10n.shapeCount(shapes.length)}');

  final pair = (shapes.first, count: shapes.length);
  final (first, count: n) = pair;
  print('${pair.$1.name} ${first.area + n}');
}
