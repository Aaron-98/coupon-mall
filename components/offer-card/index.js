Component({ properties: { offer: Object }, methods: { select() { this.triggerEvent('select', this.data.offer.id); } } });
