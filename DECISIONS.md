# Decisiones

## Supuestos

Las reservas vencen solas. No hay un job que las limpie, cada operación sobre el producto descarta las vencidas antes de calcular. Vence justo en `expiresAt`.

Si la app reintenta el mismo pedido con la misma cantidad devuelvo la reserva que ya existe. Con otra cantidad, o el mismo `orderId` en otro producto, es un error.

Confirmar dos veces falla, porque el contrato pide reserva activa y después de confirmar ya no lo está.

El aviso a compras sale una sola vez cuando el disponible llega a 5 o menos. Se vuelve a habilitar solo con `addStock`, no cuando vence una reserva. Si el listener falla se loguea y la reserva sigue, no tiene sentido tumbarle la compra al cliente porque no salió un correo.

Registrar un producto que ya existe con la misma categoría no hace nada. Con otra categoría falla.

## Cómo se cambia

Las reglas por categoría viven en `CategoryPolicies`. Para una categoría nueva se agrega al enum y una línea ahí. Hay un test que revisa que ninguna categoría quede sin regla.

Para más canales de aviso basta con un `StockAlertListener` que reparta a varios.

## Lo que dejé fuera

No hay persistencia ni cancelación de reservas. Los pedidos confirmados quedan en memoria para siempre (sirven para los reintentos). La API HTTP es mínima, sin auth, y está para poder probar con Postman.

## Antes de producción

Con base de datos y varias instancias el `synchronized` no sirve. Movería el chequeo de stock a la base, con un update condicional o lock por fila, y un índice único en `orderId` para la idempotencia. El aviso lo mandaría por una cola para no depender de que el correo responda. Y el umbral de 5 lo sacaría a configuración.
