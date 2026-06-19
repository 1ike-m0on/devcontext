class TicketRepository:
    def __init__(self):
        self.saved = []

    def save(self, ticket):
        self.saved.append(ticket)
        return ticket
